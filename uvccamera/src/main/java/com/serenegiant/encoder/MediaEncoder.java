/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.serenegiant.encoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

public abstract class MediaEncoder implements Runnable {
    private static final boolean DEBUG = false;
    private static final String TAG = "MediaEncoder";

    private static final int TIMEOUT_USEC = 10000;//10[msec]

    public interface MediaEncoderListener {
        void onPrepared(MediaEncoder encoder);

        void onStopped(MediaEncoder encoder);
    }

    protected final Object mSync = new Object();
    /**
     * Flag that indicate this encoder is capturing now.
     */
    protected volatile boolean mIsCapturing;
    /**
     * Flag that indicate the frame data will be available soon.
     */
    private int mRequestDrain;
    /**
     * Flag to request stop capturing
     */
    protected volatile boolean mRequestStop;
    /**
     * Flag that indicate encoder received EOS(End Of Stream)
     */
    protected boolean mIsEOS;
    /**
     * Flag the indicate the muxer is running
     */
    protected boolean mMuxerStarted;
    /**
     * Track Number
     */
    protected int mTrackIndex;
    /**
     * MediaCodec instance for encoding
     */
    protected MediaCodec mMediaCodec;// API >= 16(Android4.1.2)
    /**
     * Weak reference of MediaMuxerWrapper instance
     */
    private WeakReference<MediaMuxerWrapper> mWeakMuxer;
    /**
     * BufferInfo instance for dequeueing
     */
    private MediaCodec.BufferInfo mBufferInfo;// API >= 16(Android4.1.2)

    protected final MediaEncoderListener mListener;

    public MediaEncoder(MediaMuxerWrapper muxer, MediaEncoderListener listener) {
        if (listener == null) throw new NullPointerException("MediaEncoderListener is null");
        if (muxer == null) throw new NullPointerException("MediaMuxerWrapper is null");
        mWeakMuxer = new WeakReference<>(muxer);
        muxer.addEncoder(this);
        mListener = listener;
        synchronized (mSync) {
            // create BufferInfo here for effectiveness(to reduce GC)
            mBufferInfo = new MediaCodec.BufferInfo();
            // wait for starting thread
            new Thread(this, getClass().getSimpleName()).start();
            try {
                mSync.wait();
            } catch (InterruptedException ignored) {
            }
        }
    }

    public String getOutputPath() {
        MediaMuxerWrapper muxer = mWeakMuxer.get();
        return muxer != null ? muxer.getOutputPath() : null;
    }

    /**
     * the method to indicate frame data is soon available or already available
     *
     * @return return true if encoder is ready to encod.
     */
    public boolean frameAvailableSoon() {
        synchronized (mSync) {
            if (!mIsCapturing || mRequestStop) {
                return false;
            }
            mRequestDrain++;
            mSync.notifyAll();
        }
        return true;
    }

    /**
     * encoding loop on private thread
     */
    @Override
    public void run() {
        synchronized (mSync) {
            mRequestStop = false;
            mRequestDrain = 0;
            mSync.notify();
        }
        boolean localRequestStop;
        boolean localRequestDrain;
        while (true) {
            synchronized (mSync) {
                localRequestStop = mRequestStop;
                localRequestDrain = (mRequestDrain > 0);
                if (localRequestDrain)
                    mRequestDrain--;
            }
            if (localRequestStop) {
                drain();
                // request stop recording
                signalEndOfInputStream();
                // process output data again for EOS signale
                drain();
                // release all related objects
                release();
                break;
            }
            if (localRequestDrain) {
                drain();
            } else {
                synchronized (mSync) {
                    try {
                        mSync.wait();
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } // end of while
        if (DEBUG) Log.d(TAG, "Encoder thread exiting");
        synchronized (mSync) {
            mRequestStop = true;
            mIsCapturing = false;
        }
    }

    /**
     * prepare method for each sub class
     * this method should be implemented in sub class, so set this as abstract method
     */
    protected abstract void prepare() throws IOException;

    protected void startRecording() {
        if (DEBUG) Log.v(TAG, "startRecording");
        synchronized (mSync) {
            mIsCapturing = true;
            mRequestStop = false;
            mSync.notifyAll();
        }
    }

    /**
     * the method to request stop encoding
     */
    protected void stopRecording() {
        if (DEBUG) Log.v(TAG, "stopRecording");
        synchronized (mSync) {
            if (!mIsCapturing || mRequestStop) {
                return;
            }
            mRequestStop = true;// for rejecting newer frame
            mSync.notifyAll();
            // We can not know when the encoding and writing finish.
            // so we return immediately after request to avoid delay of caller thread
        }
    }

//********************************************************************************
//********************************************************************************

    /**
     * Release all release objects
     */
    protected void release() {
        if (DEBUG) Log.d(TAG, "release:");
        try {
            mListener.onStopped(this);
        } catch (Exception e) {
            Log.e(TAG, "failed onStopped", e);
        }
        mIsCapturing = false;
        if (mMediaCodec != null) {
            try {
                mMediaCodec.stop();
                mMediaCodec.release();
                mMediaCodec = null;
            } catch (Exception e) {
                Log.e(TAG, "failed releasing MediaCodec", e);
            }
        }
        if (mMuxerStarted) {
            MediaMuxerWrapper muxer = mWeakMuxer.get();
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception e) {
                    Log.e(TAG, "failed stopping muxer", e);
                }
            }
        }
        mBufferInfo = null;
    }

    private void signalEndOfInputStream() {
        if (DEBUG) Log.d(TAG, "sending EOS to encoder");
        // signalEndOfInputStream is only available for video encoding with surface
        // and equivalent sending a empty buffer with BUFFER_FLAG_END_OF_STREAM flag.
//		mMediaCodec.signalEndOfInputStream();// API >= 18
        encode((byte[]) null, 0, getPTSUs());
    }

    /**
     * Method to set byte array to the MediaCodec encoder
     *
     * @param length 　length of byte array, zero means EOS.
     */
    protected void encode(byte[] buffer, int length, long presentationTimeUs) {
        if (!mIsCapturing) return;
        int ix = 0, sz;
        ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
        while (mIsCapturing && ix < length) {
            int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                sz = inputBuffer.remaining();
                sz = (ix + sz < length) ? sz : length - ix;
                if (sz > 0 && (buffer != null)) {
                    inputBuffer.put(buffer, ix, sz);
                }
                ix += sz;
                mMediaCodec.queueInputBuffer(inputBufferIndex, 0, sz,
                        presentationTimeUs, 0);
            }
        }
    }

    /**
     * Method to set ByteBuffer to the MediaCodec encoder
     *
     * @param buffer null means EOS
     */
    protected void encode(ByteBuffer buffer, int length, long presentationTimeUs) {
        if (!mIsCapturing) return;
        int ix = 0, sz;
        ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
        while (mIsCapturing && ix < length) {
            int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                sz = inputBuffer.remaining();
                sz = (ix + sz < length) ? sz : length - ix;
                if (sz > 0 && (buffer != null)) {
                    buffer.position(ix + sz);
                    buffer.flip();
                    inputBuffer.put(buffer);
                }
                ix += sz;
                mMediaCodec.queueInputBuffer(inputBufferIndex, 0, sz,
                        presentationTimeUs, 0);
            }
        }
    }

    /**
     * drain encoded data and write them to muxer
     */
    private void drain() {
        if (mMediaCodec == null) return;
        ByteBuffer[] encoderOutputBuffers = mMediaCodec.getOutputBuffers();
        int encoderStatus, count = 0;
        MediaMuxerWrapper muxer = mWeakMuxer.get();
        if (muxer == null) {
            Log.w(TAG, "muxer is unexpectedly null");
            return;
        }
        while (mIsCapturing) {
            // get encoded data with maximum timeout duration of TIMEOUT_USEC(=10[msec])
            encoderStatus = mMediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // wait 5 counts(=TIMEOUT_USEC x 5 = 50msec) until data/EOS come
                if (!mIsEOS) {
                    if (++count > 5)
                        break;        // out of while
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                if (DEBUG) Log.v(TAG, "INFO_OUTPUT_BUFFERS_CHANGED");
                // this shoud not come when encoding
                encoderOutputBuffers = mMediaCodec.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (DEBUG) Log.v(TAG, "INFO_OUTPUT_FORMAT_CHANGED");
                // this status indicate the output format of codec is changed
                // this should come only once before actual encoded data
                // but this status never come on Android4.3 or less
                // and in that case, you should treat when MediaCodec.BUFFER_FLAG_CODEC_CONFIG come.
                if (mMuxerStarted) {    // second time request is error
                    throw new RuntimeException("format changed twice");
                }
                // get output format from codec and pass them to muxer
                // getOutputFormat should be called after INFO_OUTPUT_FORMAT_CHANGED otherwise crash.
                MediaFormat format = mMediaCodec.getOutputFormat(); // API >= 16
                mTrackIndex = muxer.addTrack(format);
                mMuxerStarted = true;
                if (!muxer.start()) {
                    // we should wait until muxer is ready
                    synchronized (muxer) {
                        while (!muxer.isStarted())
                            try {
                                muxer.wait(100);
                            } catch (InterruptedException e) {
                                break;
                            }
                    }
                }
            } else if (encoderStatus < 0) {
                // unexpected status
                if (DEBUG)
                    Log.w(TAG, "drain:unexpected result from encoder#dequeueOutputBuffer: " + encoderStatus);
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    // this never should come...may be a MediaCodec internal error
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                }
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // You shoud set output format to muxer here when you target Android4.3 or less
                    // but MediaCodec#getOutputFormat can not call here(because INFO_OUTPUT_FORMAT_CHANGED don't come yet)
                    // therefor we should expand and prepare output format from buffer data.
                    // This sample is for API>=18(>=Android 4.3), just ignore this flag here
                    if (DEBUG) Log.d(TAG, "drain:BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    // encoded data is ready, clear waiting counter
                    count = 0;
                    if (!mMuxerStarted) {
                        // muxer is not ready...this will prrograming failure.
                        throw new RuntimeException("drain:muxer hasn't started");
                    }
                    // write encoded data to muxer(need to adjust presentationTimeUs.
                    mBufferInfo.presentationTimeUs = getPTSUs();
                    muxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    prevOutputPTSUs = mBufferInfo.presentationTimeUs;
                }
                // return buffer to encoder
                mMediaCodec.releaseOutputBuffer(encoderStatus, false);
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    // when EOS come.
                    mMuxerStarted = mIsCapturing = false;
                    break;      // out of while
                }
            }
        }
    }

    /**
     * previous presentationTimeUs for writing
     */
    private long prevOutputPTSUs = 0;

    /**
     * get next encoding presentationTimeUs
     */
    protected long getPTSUs() {
        long result = System.nanoTime() / 1000L;
        // presentationTimeUs should be monotonic
        // otherwise muxer fail to write
        if (result < prevOutputPTSUs)
            result = (prevOutputPTSUs - result) + result;
        return result;
    }

}
