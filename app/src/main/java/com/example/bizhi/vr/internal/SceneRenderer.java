/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.example.bizhi.vr.internal;

import static com.google.android.exoplayer2.util.GlUtil.checkGlError;
import static com.google.android.exoplayer2.util.Assertions.checkNotNull;

import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.util.GlUtil;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.TimedValueQueue;
import com.google.android.exoplayer2.video.VideoFrameMetadataListener;
import com.google.android.exoplayer2.video.spherical.CameraMotionListener;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Renders a GL Scene. */
public final class SceneRenderer
    implements VideoFrameMetadataListener, CameraMotionListener {

  private static final String TAG = "SceneRenderer";
  private static final Handler FRAME_CALLBACK_HANDLER = new Handler(Looper.getMainLooper());

  private final AtomicBoolean frameAvailable;
  private final AtomicBoolean resetRotationAtNextFrame;
  private final ProjectionRenderer projectionRenderer;
  private final FrameRotationQueue frameRotationQueue;
  private final TimedValueQueue<Long> sampleTimestampQueue;
  private final TimedValueQueue<Projection> projectionQueue;
  private final float[] rotationMatrix;
  private final float[] tempMatrix;
  private boolean metadataLogged;
  private boolean frameAvailableLogged;
  private volatile int lastVideoWidth;
  private volatile int lastVideoHeight;
  private volatile float lastPixelRatio = 1f;

  // Used by GL thread only
  private int textureId;
  @Nullable private SurfaceTexture surfaceTexture;

  // Used by other threads only
  private volatile @C.StereoMode int defaultStereoMode;
  private @C.StereoMode int lastStereoMode;
  @Nullable private byte[] lastProjectionData;

  // Methods called on any thread.

  public SceneRenderer() {
    frameAvailable = new AtomicBoolean();
    resetRotationAtNextFrame = new AtomicBoolean(true);
    projectionRenderer = new ProjectionRenderer();
    frameRotationQueue = new FrameRotationQueue();
    sampleTimestampQueue = new TimedValueQueue<>();
    projectionQueue = new TimedValueQueue<>();
    rotationMatrix = new float[16];
    tempMatrix = new float[16];
    defaultStereoMode = C.STEREO_MODE_MONO;
    lastStereoMode = Format.NO_VALUE;
    projectionRenderer.setProjection(Projection.createEquirectangular(defaultStereoMode));
  }

  /**
   * Sets the default stereo mode. If the played video doesn't contain a stereo mode the default one
   * is used.
   *
   * @param stereoMode A {@link C.StereoMode} value.
   */
  public void setDefaultStereoMode(@C.StereoMode int stereoMode) {
    defaultStereoMode = stereoMode;
    projectionRenderer.setProjection(Projection.createEquirectangular(defaultStereoMode));
  }

  // Methods called on GL thread.

  /** Initializes the renderer. */
  public SurfaceTexture init() {
    try {
      // Set the background frame color. This is only visible if the display mesh isn't a full
      // sphere.
      GLES20.glClearColor(0.5f, 0.5f, 0.5f, 1.0f);
      checkGlError();

      projectionRenderer.init();
      checkGlError();

      projectionRenderer.setProjection(Projection.createEquirectangular(defaultStereoMode));

      textureId = GlUtil.createExternalTexture();
    } catch (GlUtil.GlException e) {
      Log.e(TAG, "Failed to initialize the renderer", e);
    }
    surfaceTexture = new SurfaceTexture(textureId);
    surfaceTexture.setOnFrameAvailableListener(
        surfaceTexture -> frameAvailable.set(true), FRAME_CALLBACK_HANDLER);
    return surfaceTexture;
  }

  /**
   * Draws the scene with a given eye pose and type.
   *
   * @param viewProjectionMatrix 16 element GL matrix.
   * @param rightEye Whether the right eye view should be drawn. If {@code false}, the left eye view
   *     is drawn.
   */
  public void drawFrame(float[] viewProjectionMatrix, boolean rightEye) {
    // glClear isn't strictly necessary when rendering fully spherical panoramas, but it can improve
    // performance on tiled renderers by causing the GPU to discard previous data.
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    try {
      checkGlError();
    } catch (GlUtil.GlException e) {
      Log.e(TAG, "Failed to draw a frame", e);
    }

    if (frameAvailable.compareAndSet(true, false)) {
      checkNotNull(surfaceTexture).updateTexImage();
      if (!frameAvailableLogged) {
        Log.d(TAG, "First frame available from SurfaceTexture");
        frameAvailableLogged = true;
      }
      try {
        checkGlError();
      } catch (GlUtil.GlException e) {
        Log.e(TAG, "Failed to draw a frame", e);
      }
      if (resetRotationAtNextFrame.compareAndSet(true, false)) {
        GlUtil.setToIdentity(rotationMatrix);
      }
      long lastFrameTimestampNs = surfaceTexture.getTimestamp();
      Long sampleTimestampUs = sampleTimestampQueue.poll(lastFrameTimestampNs);
      if (sampleTimestampUs != null) {
        frameRotationQueue.pollRotationMatrix(rotationMatrix, sampleTimestampUs);
      }
      Projection projection = projectionQueue.pollFloor(lastFrameTimestampNs);
      if (projection != null) {
        projectionRenderer.setProjection(projection);
      }
    }
    Matrix.multiplyMM(tempMatrix, 0, viewProjectionMatrix, 0, rotationMatrix, 0);
    projectionRenderer.draw(textureId, tempMatrix, rightEye);
  }

  /** Cleans up GL resources. */
  public void shutdown() {
    projectionRenderer.shutdown();
  }

  // Methods called on playback thread.

  // VideoFrameMetadataListener implementation.

  @Override
  public void onVideoFrameAboutToBeRendered(
      long presentationTimeUs,
      long releaseTimeNs,
      Format format,
      @Nullable MediaFormat mediaFormat) {
    if (!metadataLogged) {
      Log.d(TAG, "Received first video frame metadata");
      metadataLogged = true;
    }
    sampleTimestampQueue.add(releaseTimeNs, presentationTimeUs);
    setProjection(format.projectionData, format.stereoMode, releaseTimeNs);
    if (format.width > 0 && format.height > 0) {
      lastVideoWidth = format.width;
      lastVideoHeight = format.height;
      lastPixelRatio = format.pixelWidthHeightRatio > 0 ? format.pixelWidthHeightRatio : 1f;
    }
  }

  /** Returns the GL texture id used for the external video texture. */
  public int getTextureId() {
    return textureId;
  }

  /** Returns the last known video aspect ratio (width * pixelRatio / height). */
  public float getVideoAspectRatio() {
    int width = lastVideoWidth;
    int height = lastVideoHeight;
    if (width <= 0 || height <= 0) {
      return 1f;
    }
    return (width * lastPixelRatio) / height;
  }

  /** Updates the SurfaceTexture if a frame is available, without rendering spherical geometry. */
  public boolean updateFrameIfAvailable() {
    if (!frameAvailable.compareAndSet(true, false)) {
      return false;
    }
    if (surfaceTexture == null) {
      return false;
    }
    surfaceTexture.updateTexImage();
    if (!frameAvailableLogged) {
      Log.d(TAG, "First frame available from SurfaceTexture");
      frameAvailableLogged = true;
    }
    long lastFrameTimestampNs = surfaceTexture.getTimestamp();
    Long sampleTimestampUs = sampleTimestampQueue.poll(lastFrameTimestampNs);
    if (sampleTimestampUs != null) {
      frameRotationQueue.pollRotationMatrix(rotationMatrix, sampleTimestampUs);
    }
    Projection projection = projectionQueue.pollFloor(lastFrameTimestampNs);
    if (projection != null) {
      projectionRenderer.setProjection(projection);
    }
    return true;
  }

  // CameraMotionListener implementation.

  @Override
  public void onCameraMotion(long timeUs, float[] rotation) {
    frameRotationQueue.setRotation(timeUs, rotation);
  }

  @Override
  public void onCameraMotionReset() {
    sampleTimestampQueue.clear();
    frameRotationQueue.reset();
    resetRotationAtNextFrame.set(true);
  }

  /**
   * Sets projection data and stereo mode of the media to be played.
   *
   * @param projectionData Contains the projection data to be rendered.
   * @param stereoMode A {@link C.StereoMode} value.
   * @param timeNs When then new projection should be used.
   */
  private void setProjection(
      @Nullable byte[] projectionData, @C.StereoMode int stereoMode, long timeNs) {
    byte[] oldProjectionData = lastProjectionData;
    int oldStereoMode = lastStereoMode;
    lastProjectionData = projectionData;
    lastStereoMode = stereoMode == Format.NO_VALUE ? defaultStereoMode : stereoMode;
    if (oldStereoMode == lastStereoMode && Arrays.equals(oldProjectionData, lastProjectionData)) {
      return;
    }

    Projection projectionFromData = null;
    if (lastProjectionData != null) {
      projectionFromData = ProjectionDecoder.decode(lastProjectionData, lastStereoMode);
    }
    Projection projection =
        projectionFromData != null && ProjectionRenderer.isSupported(projectionFromData)
            ? projectionFromData
            : Projection.createEquirectangular(lastStereoMode);
    projectionQueue.add(timeNs, projection);
  }
}
