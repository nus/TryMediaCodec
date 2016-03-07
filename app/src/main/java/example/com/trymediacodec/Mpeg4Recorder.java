/*
 * Copyright 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * The above license is under http://bigflake.com/mediacodec/EncodeAndMuxTest.java.txt
 */

package example.com.trymediacodec;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

public class Mpeg4Recorder {
    private static final boolean VERBOSE = true;
    private static final String TAG = Mpeg4Recorder.class.getName();
    private static final String MIME_TYPE = "video/avc"; // H.264
    private static final int BIT_RATE = 2000000; // [bit/sec]
    private static final int IFRAME_INTERVAL = 10; // in seconds between I-frames
    private final String _path;
    private final int _width;
    private final int _height;
    private final int _frameRate;
    private final ICallback _callback;
    private final MediaCodec.BufferInfo _bufferInfo;
    private MediaCodec _encoder = null;
    private CodecInputSurface _inputSurface = null;
    private MediaMuxer _muxer = null;
    private int _videoTrackIndex = -1;
    private boolean _muxerStarted = false;
    private Thread _recordingThread;

    public interface ICallback {
        void onDrawOnEgl();
    }

    public Mpeg4Recorder(String path, int width, int height, int frameRate, ICallback callback) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path must be not empty.");
        } else if (!path.endsWith(".mp4")) {
            throw new IllegalArgumentException("the extension must be \".mp4\"");
        } else if (!passedLength(width)) {
            throw new IllegalArgumentException("width is invalid.");
        } else if (!passedLength(height)) {
            throw new IllegalArgumentException("height is invalid.");
        } else if (frameRate < 1) {
            throw new IllegalArgumentException("frameRate is invalid.");
        } else if (callback == null) {
            throw new IllegalArgumentException("callback must be not null.");
        }

        _path = path;
        _width = width;
        _height = height;
        _frameRate = frameRate;
        _callback = callback;

        _bufferInfo = new MediaCodec.BufferInfo();
    }

    public boolean prepare() {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, _width, _height);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, _frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        //
        // If you want to have two EGL contexts -- one for display, one for recording --
        // you will likely want to defer instantiation of CodecInputSurface until after the
        // "display" EGL context is created, then modify the eglCreateContext call to
        // take eglGetCurrentContext() as the share_context argument.
        try {
            _encoder = MediaCodec.createEncoderByType(MIME_TYPE);
        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            return false;
        }
        _encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        _inputSurface = new CodecInputSurface(_encoder.createInputSurface());
        _encoder.start();

        try {
            _muxer = new MediaMuxer(_path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
            return false;
        }

        _videoTrackIndex = -1;
        _muxerStarted = false;

        return true;
    }

    public void start() {
        _recordingThread = new Thread() {
            @Override
            public void run() {
                try {
                    _inputSurface.makeCurrent();

                    for (int i = 0; i < 300; i++) {
                        drainEncoder(false);

                        _callback.onDrawOnEgl();
                        _inputSurface.setPresentationTime(computePresentationTimeNsec(i));

                        if (VERBOSE) Log.d(TAG, "sending frame " + i + " to encoder");
                        _inputSurface.swapBuffers();
                    }

                    drainEncoder(true);
                } finally {
                    release();
                }
            }
        };
        _recordingThread.start();
    }

    private void release() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        if (_encoder != null) {
            _encoder.stop();
            _encoder.release();
            _encoder = null;
        }
        if (_inputSurface != null) {
            _inputSurface.release();
            _inputSurface = null;
        }
        if (_muxer != null) {
            _muxer.stop();
            _muxer.release();
            _muxer = null;
        }
    }

    private boolean passedLength(int len) {
        if (len < 16) {
            return false;
        } else if ((len % 16) != 0) {
            return false;
        } else {
            return true;
        }
    }

    private void drainEncoder(boolean endOfStream) {
        final int TIMEOUT_USEC = 10000;

        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            _encoder.signalEndOfInputStream();
        }

        ByteBuffer[] encoderOutputBuffers = _encoder.getOutputBuffers();
        while (true) {
            int encoderStatus = _encoder.dequeueOutputBuffer(_bufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = _encoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (_muxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = _encoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);

                // now that we have the Magic Goodies, start the muxer
                _videoTrackIndex = _muxer.addTrack(newFormat);
                _muxer.start();
                _muxerStarted = true;
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                if (VERBOSE) Log.d(TAG, String.format("flags: %d, offset:%d, p:%d, size:%d", _bufferInfo.flags, _bufferInfo.offset, _bufferInfo.presentationTimeUs, _bufferInfo.size));

                if ((_bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    _bufferInfo.size = 0;
                }

                if (_bufferInfo.size != 0) {
                    if (!_muxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(_bufferInfo.offset);
                    encodedData.limit(_bufferInfo.offset + _bufferInfo.size);

                    _muxer.writeSampleData(_videoTrackIndex, encodedData, _bufferInfo);
                    if (VERBOSE) Log.d(TAG, "sent " + _bufferInfo.size + " bytes to muxer");
                }

                _encoder.releaseOutputBuffer(encoderStatus, false);

                if ((_bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    break;      // out of while
                }
            }
        }
    }

    private long computePresentationTimeNsec(int frameIndex) {
        final long ONE_BILLION = 1000000000;
        return frameIndex * ONE_BILLION / _frameRate;
    }

    private static class CodecInputSurface {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;

        private EGLDisplay _eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext _eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface _eglSurface = EGL14.EGL_NO_SURFACE;

        private Surface _surface;

        /**
         * Creates a CodecInputSurface from a Surface.
         */
        public CodecInputSurface(Surface surface) {
            if (surface == null) {
                throw new NullPointerException();
            }
            _surface = surface;

            eglSetup();
        }

        /**
         * Prepares EGL.  We want a GLES 2.0 context and a surface that supports recording.
         */
        private void eglSetup() {
            _eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (_eglDisplay == EGL14.EGL_NO_DISPLAY) {
                throw new RuntimeException("unable to get EGL14 display");
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(_eglDisplay, version, 0, version, 1)) {
                throw new RuntimeException("unable to initialize EGL14");
            }

            // Configure EGL for recording and OpenGL ES 2.0.
            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(_eglDisplay, attribList, 0, configs, 0, configs.length,
                    numConfigs, 0);
            checkEglError("eglCreateContext RGB888+recordable ES2");

            // Configure context for OpenGL ES 2.0.
            int[] attrib_list = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            _eglContext = EGL14.eglCreateContext(_eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                    attrib_list, 0);
            checkEglError("eglCreateContext");

            // Create a window surface, and attach it to the Surface we received.
            int[] surfaceAttribs = {
                    EGL14.EGL_NONE
            };
            _eglSurface = EGL14.eglCreateWindowSurface(_eglDisplay, configs[0], _surface,
                    surfaceAttribs, 0);
            checkEglError("eglCreateWindowSurface");
        }

        /**
         * Discards all resources held by this class, notably the EGL context.  Also releases the
         * Surface that was passed to our constructor.
         */
        public void release() {
            if (_eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(_eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(_eglDisplay, _eglSurface);
                EGL14.eglDestroyContext(_eglDisplay, _eglContext);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(_eglDisplay);
            }

            _surface.release();

            _eglDisplay = EGL14.EGL_NO_DISPLAY;
            _eglContext = EGL14.EGL_NO_CONTEXT;
            _eglSurface = EGL14.EGL_NO_SURFACE;

            _surface = null;
        }

        /**
         * Makes our EGL context and surface current.
         */
        public void makeCurrent() {
            EGL14.eglMakeCurrent(_eglDisplay, _eglSurface, _eglSurface, _eglContext);
            checkEglError("eglMakeCurrent");
        }

        /**
         * Calls eglSwapBuffers.  Use this to "publish" the current frame.
         */
        public boolean swapBuffers() {
            boolean result = EGL14.eglSwapBuffers(_eglDisplay, _eglSurface);
            checkEglError("eglSwapBuffers");
            return result;
        }

        /**
         * Sends the presentation time stamp to EGL.  Time is expressed in nanoseconds.
         */
        public void setPresentationTime(long nsecs) {
            EGLExt.eglPresentationTimeANDROID(_eglDisplay, _eglSurface, nsecs);
            checkEglError("eglPresentationTimeANDROID");
        }

        /**
         * Checks for EGL errors.  Throws an exception if one is found.
         */
        private void checkEglError(String msg) {
            int error;
            if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
                throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
            }
        }
    }
}
