/*---------------------------------------------------------------------------*
 *  MicrophoneInputStream.java                                               *
 *                                                                           *
 *  Copyright 2007 Nuance Communciations, Inc.                               *
 *                                                                           *
 *  Licensed under the Apache License, Version 2.0 (the 'License');          *
 *  you may not use this file except in compliance with the License.         *
 *                                                                           *
 *  You may obtain a copy of the License at                                  *
 *      http://www.apache.org/licenses/LICENSE-2.0                           *
 *                                                                           *
 *  Unless required by applicable law or agreed to in writing, software      *
 *  distributed under the License is distributed on an 'AS IS' BASIS,        *
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. *
 *  See the License for the specific language governing permissions and      *
 *  limitations under the License.                                           *
 *                                                                           *
 *---------------------------------------------------------------------------*/


package android.speech.srec;

import java.io.IOException;
import java.io.InputStream;
import java.lang.IllegalStateException;

//BEGIN PRIVACY
import android.privacy.IPrivacySettingsManager;
import android.privacy.PrivacyServiceException;
import android.privacy.IPrivacySettings;
import android.privacy.PrivacySettings;
import android.privacy.PrivacySettingsManager;

import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;

import android.os.Process;
import android.os.ServiceManager;
import android.util.Log;
//END PRIVACY


/**
 * PCM input stream from the microphone, 16 bits per sample.
 */
public final class MicrophoneInputStream extends InputStream {
    static {
        System.loadLibrary("srec_jni");
    }


    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //BEGIN PRIVACY 

    private static final int IS_ALLOWED = -1;
    private static final int IS_NOT_ALLOWED = -2;
    private static final int GOT_ERROR = -3;

    private static final String PRIVACY_TAG = "PM,MicrophoneInputStream";

    private PrivacySettingsManager mPrvSvc;

    private boolean privacyMode = false;

    private IPackageManager mPm;

    //END PRIVACY
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


    private final static String TAG = "MicrophoneInputStream";
    private int mAudioRecord = 0;
    private byte[] mOneByte = new byte[1];


    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //BEGIN PRIVACY
    /**
     * {@hide}
     * This method sets up all variables which are needed for privacy mode! It also writes to privacyMode, if everything was successfull or not! 
     * -> privacyMode = true ok! otherwise false!
     * CALL THIS METHOD IN CONSTRUCTOR!
     */
    private void initiate(){
        try{
            if (mPrvSvc == null) mPrvSvc = PrivacySettingsManager.getPrivacyService();
            mPm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
            privacyMode = true;
        }
        catch(Exception e){
            e.printStackTrace();
            Log.e(PRIVACY_TAG, "Something went wrong with initalize variables");
            privacyMode = false;
        }
    }
    /**
     * {@hide}
     * This method should be used, because in some devices the uid has more than one package within!
     * @return IS_ALLOWED (-1) if all packages allowed, IS_NOT_ALLOWED(-2) if one of these packages not allowed, GOT_ERROR (-3) if something went wrong
     */
    private int checkIfPackagesAllowed(){
        try{
            int uid = Process.myUid();
            if (mPrvSvc == null) mPrvSvc = PrivacySettingsManager.getPrivacyService();
            IPrivacySettings pSet = null;
            pSet = mPrvSvc.getSettingsSafe(uid);
            if(PrivacySettings.getOutcome(pSet.getRecordAudioSetting()) == IPrivacySettings.REAL){
                return IS_ALLOWED;
            } else {
                return IS_NOT_ALLOWED;
            }
        } catch (Exception e){
            Log.e(PRIVACY_TAG,"Camera:checkIfPackagesAllowed: Got exception in checkIfPackagesAllowed", e);
            return GOT_ERROR;
        }
    }



    //END PRIVACY
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////



    /**
     * MicrophoneInputStream constructor.
     * @param sampleRate sample rate of the microphone, typically 11025 or 8000.
     * @param fifoDepth depth of the real time fifo, measured in sampleRate clock ticks.
     * This determines how long an application may delay before losing data.
     */
    public MicrophoneInputStream(int sampleRate, int fifoDepth) throws IOException {


        //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
        //BEGIN PRIVACY
        int uid = Process.myUid();
        if(!privacyMode){
            initiate();
        }

        switch (checkIfPackagesAllowed()) {
        case IS_ALLOWED:
            mPrvSvc.notification(uid, PrivacySettings.REAL, PrivacySettings.DATA_RECORD_AUDIO, null);
            break;
        default:
            mPrvSvc.notification(uid, PrivacySettings.EMPTY, PrivacySettings.DATA_RECORD_AUDIO, null);
            throw new IOException("AudioRecord constructor failed - busy?");
        }
        //END PRIVACY
        //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


        mAudioRecord = AudioRecordNew(sampleRate, fifoDepth);
        if (mAudioRecord == 0) throw new IOException("AudioRecord constructor failed - busy?");
        int status = AudioRecordStart(mAudioRecord);
        if (status != 0) {
            close();
            throw new IOException("AudioRecord start failed: " + status);
        }
    }

    @Override
    public int read() throws IOException {
        if (mAudioRecord == 0) throw new IllegalStateException("not open");
        int rtn = AudioRecordRead(mAudioRecord, mOneByte, 0, 1);
        return rtn == 1 ? ((int)mOneByte[0] & 0xff) : -1;
    }

    @Override
    public int read(byte[] b) throws IOException {
        if (mAudioRecord == 0) throw new IllegalStateException("not open");
        return AudioRecordRead(mAudioRecord, b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int offset, int length) throws IOException {
        if (mAudioRecord == 0) throw new IllegalStateException("not open");
        // TODO: should we force all reads to be a multiple of the sample size?
        return AudioRecordRead(mAudioRecord, b, offset, length);
    }

    /**
     * Closes this stream.
     */
    @Override
    public void close() throws IOException {
        if (mAudioRecord != 0) {
            try {
                AudioRecordStop(mAudioRecord);
            } finally {
                try {
                    AudioRecordDelete(mAudioRecord);
                } finally {
                    mAudioRecord = 0;
                }
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (mAudioRecord != 0) {
            close();
            throw new IOException("someone forgot to close MicrophoneInputStream");
        }
    }

    //
    // AudioRecord JNI interface
    //
    private static native int AudioRecordNew(int sampleRate, int fifoDepth);
    private static native int AudioRecordStart(int audioRecord);
    private static native int AudioRecordRead(int audioRecord, byte[] b, int offset, int length) throws IOException;
    private static native void AudioRecordStop(int audioRecord) throws IOException;
    private static native void AudioRecordDelete(int audioRecord) throws IOException;
}
