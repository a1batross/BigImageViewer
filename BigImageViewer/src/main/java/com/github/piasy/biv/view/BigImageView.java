/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Piasy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.piasy.biv.view;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.annotation.Keep;
import androidx.annotation.RequiresPermission;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;
import com.github.piasy.biv.BigImageViewer;
import com.github.piasy.biv.R;
import com.github.piasy.biv.indicator.ProgressIndicator;
import com.github.piasy.biv.loader.ImageLoader;
import com.github.piasy.biv.metadata.ImageInfoExtractor;
import com.github.piasy.biv.utils.DisplayOptimizeListener;
import com.github.piasy.biv.utils.IOUtils;
import com.github.piasy.biv.utils.ThreadedCallbacks;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Created by Piasy{github.com/Piasy} on 06/11/2016.
 * <p>
 * Use FrameLayout for extensibility.
 */

@Keep
public class BigImageView extends FrameLayout implements ImageLoader.Callback {
    public static final int INIT_SCALE_TYPE_CENTER = 0;
    public static final int INIT_SCALE_TYPE_CENTER_CROP = 1;
    public static final int INIT_SCALE_TYPE_CENTER_INSIDE = 2;
    public static final int INIT_SCALE_TYPE_FIT_CENTER = 3;
    public static final int INIT_SCALE_TYPE_FIT_END = 4;
    public static final int INIT_SCALE_TYPE_FIT_START = 5;
    public static final int INIT_SCALE_TYPE_FIT_XY = 6;
    public static final int INIT_SCALE_TYPE_CUSTOM = 7;
    public static final int INIT_SCALE_TYPE_START = 8;

    public static final int DEFAULT_IMAGE_SCALE_TYPE = 3;
    public static final ImageView.ScaleType[] IMAGE_SCALE_TYPES = {
            ImageView.ScaleType.CENTER,
            ImageView.ScaleType.CENTER_CROP,
            ImageView.ScaleType.CENTER_INSIDE,
            ImageView.ScaleType.FIT_CENTER,
            ImageView.ScaleType.FIT_END,
            ImageView.ScaleType.FIT_START,
            ImageView.ScaleType.FIT_XY,
    };

    private final ImageLoader mImageLoader;
    private final ImageLoader.Callback mInternalCallback;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());

    private ImageViewFactory mViewFactory;

    private View mMainView;
    private View mThumbnailView;
    private SubsamplingScaleImageView mSSIV;

    private View mProgressIndicatorView;
    private ImageView mFailureImageView;

    private boolean mDelayMainImageForTransition = false;

    private ImageSaveCallback mImageSaveCallback;
    private ImageShownCallback mImageShownCallback;
    private ImageLoader.Callback mUserCallback;
    private File mCurrentImageFile;
    private Uri mUri;
    private Uri mThumbnail;

    private OnClickListener mOnClickListener;
    private OnLongClickListener mOnLongClickListener;
    private ProgressIndicator mProgressIndicator;
    private DisplayOptimizeListener mDisplayOptimizeListener;
    private int mInitScaleType;
    private ImageView.ScaleType mThumbnailScaleType;
    private ImageView.ScaleType mFailureImageScaleType;
    private boolean mOptimizeDisplay;
    private boolean mTapToRetry;

    private final OnClickListener mFailureImageClickListener = new OnClickListener() {
        @Override
        public void onClick(final View v) {
            // Retry loading when failure image is clicked
            if (mTapToRetry) {
                showImage(mThumbnail, mUri);
            } else if (mOnClickListener != null) {
                mOnClickListener.onClick(v);
            }
        }
    };

    public BigImageView(Context context) {
        this(context, null);
    }

    public BigImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BigImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        TypedArray array = context.getTheme()
                .obtainStyledAttributes(attrs, R.styleable.BigImageView, defStyleAttr, 0);
        mInitScaleType = array.getInteger(R.styleable.BigImageView_initScaleType,
                INIT_SCALE_TYPE_FIT_CENTER);

        if (array.hasValue(R.styleable.BigImageView_failureImage)) {
            int scaleTypeIndex = array.getInteger(
                    R.styleable.BigImageView_failureImageInitScaleType,
                    DEFAULT_IMAGE_SCALE_TYPE);
            mFailureImageScaleType = scaleType(scaleTypeIndex);
            Drawable mFailureImageDrawable = array.getDrawable(
                    R.styleable.BigImageView_failureImage);
            setFailureImage(mFailureImageDrawable);
        }
        if (array.hasValue(R.styleable.BigImageView_thumbnailScaleType)) {
            int scaleTypeIndex = array.getInteger(
                    R.styleable.BigImageView_thumbnailScaleType,
                    DEFAULT_IMAGE_SCALE_TYPE);
            mThumbnailScaleType = scaleType(scaleTypeIndex);
        }

        mOptimizeDisplay = array.getBoolean(R.styleable.BigImageView_optimizeDisplay, true);
        mTapToRetry = array.getBoolean(R.styleable.BigImageView_tapToRetry, true);

        array.recycle();

        if (isInEditMode()) {
            mImageLoader = null;
        } else {
            mImageLoader = BigImageViewer.imageLoader();
        }
        mInternalCallback = ThreadedCallbacks.create(ImageLoader.Callback.class, this);

        mViewFactory = new ImageViewFactory();
    }

    public static ImageView.ScaleType scaleType(int value) {
        if (0 <= value && value < IMAGE_SCALE_TYPES.length) {
            return IMAGE_SCALE_TYPES[value];
        }
        return IMAGE_SCALE_TYPES[DEFAULT_IMAGE_SCALE_TYPE];
    }

    @Override
    public void setOnClickListener(final OnClickListener listener) {
        mOnClickListener = listener;
        if (mMainView != null) {
            mMainView.setOnClickListener(listener);
        }
    }

    @Override
    public void setOnLongClickListener(OnLongClickListener listener) {
        mOnLongClickListener = listener;
        if (mMainView != null) {
            mMainView.setOnLongClickListener(listener);
        }
    }

    public void setImageViewFactory(ImageViewFactory viewFactory) {
        if (viewFactory == null) {
            return;
        }

        mViewFactory = viewFactory;
    }

    public void setFailureImageInitScaleType(ImageView.ScaleType scaleType) {
        mFailureImageScaleType = scaleType;
    }

    public void setFailureImage(Drawable failureImage) {
        // Failure image is not set
        if (failureImage == null) {
            return;
        }

        if (mFailureImageView == null) {
            // Init failure image
            mFailureImageView = new ImageView(getContext());
            mFailureImageView.setVisibility(GONE);
            mFailureImageView.setOnClickListener(mFailureImageClickListener);

            if (mFailureImageScaleType != null) {
                mFailureImageView.setScaleType(mFailureImageScaleType);
            }

            addView(mFailureImageView);
        }

        mFailureImageView.setImageDrawable(failureImage);
    }

    public void setInitScaleType(int initScaleType) {
        if (mSSIV == null) {
            return;
        }

        mInitScaleType = initScaleType;
        switch (initScaleType) {
            case INIT_SCALE_TYPE_CENTER_CROP:
                mSSIV.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP);
                break;
            case INIT_SCALE_TYPE_CUSTOM:
                mSSIV.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM);
                break;
            case INIT_SCALE_TYPE_START:
                mSSIV.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_START);
                break;
            case INIT_SCALE_TYPE_CENTER_INSIDE:
            default:
                mSSIV.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE);
                break;
        }
        if (mDisplayOptimizeListener != null) {
            mDisplayOptimizeListener.setInitScaleType(initScaleType);
        }
    }

    public void setThumbnailScaleType(ImageView.ScaleType scaleType) {
        mThumbnailScaleType = scaleType;
    }

    public void setOptimizeDisplay(boolean optimizeDisplay) {
        if (mSSIV == null) {
            return;
        }

        mOptimizeDisplay = optimizeDisplay;
        if (mOptimizeDisplay) {
            mDisplayOptimizeListener = new DisplayOptimizeListener(mSSIV);
            mSSIV.setOnImageEventListener(mDisplayOptimizeListener);
        } else {
            mDisplayOptimizeListener = null;
            mSSIV.setOnImageEventListener(null);
        }
    }

    public void setTapToRetry(boolean tapToRetry) {
        mTapToRetry = tapToRetry;
    }

    public void setImageSaveCallback(ImageSaveCallback imageSaveCallback) {
        mImageSaveCallback = imageSaveCallback;
    }

    public void setImageShownCallback(ImageShownCallback imageCycleCallback) {
        mImageShownCallback = imageCycleCallback;
    }

    public void setProgressIndicator(ProgressIndicator progressIndicator) {
        mProgressIndicator = progressIndicator;
    }

    public void setImageLoaderCallback(ImageLoader.Callback imageLoaderCallback) {
        mUserCallback = imageLoaderCallback;
    }

    public File getCurrentImageFile() {
        return mCurrentImageFile;
    }

    @WorkerThread
    @RequiresPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    public void saveImageIntoGallery() {
        if (mCurrentImageFile == null) {
            fireSaveImageCallback(null, new IllegalStateException("image not downloaded yet"));
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            OutputStream outputStream = null;
            FileInputStream inputStream = null;
            Uri imageUri = null;
            boolean saved = false;
            try {
                ContentResolver resolver = getContext().getContentResolver();
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME,
                    mCurrentImageFile.getName());
                // this mime type doesn't really matter, so we just use jpg.
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES);
                imageUri =
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);

                if (imageUri != null) {
                    outputStream = resolver.openOutputStream(imageUri);
                    inputStream = new FileInputStream(mCurrentImageFile);
                    // a simple file copy is enough.
                    IOUtils.copy(inputStream, outputStream);
                    saved = true;
                } else {
                    fireSaveImageCallback(null, new RuntimeException(
                        "saveImageIntoGallery fail: insert to MediaStore error"));
                }
            } catch (IOException e) {
                fireSaveImageCallback(null, e);
            } finally {
                IOUtils.closeQuietly(inputStream);
                IOUtils.closeQuietly(outputStream);
            }
            if (saved) {
                fireSaveImageCallback(imageUri.toString(), null);
            }
        } else {
            try {
                String result =
                    MediaStore.Images.Media.insertImage(
                        getContext().getContentResolver(),
                        mCurrentImageFile.getAbsolutePath(),
                        mCurrentImageFile.getName(),
                        ""
                    );
                fireSaveImageCallback(result, null);
            } catch (IOException e) {
                fireSaveImageCallback(null, e);
            }
        }
    }

    @WorkerThread
    private void fireSaveImageCallback(final String uri, final Throwable error) {
        final ImageSaveCallback imageSaveCallback = mImageSaveCallback;
        if (imageSaveCallback != null) {
            mUiHandler.post(new Runnable() {
                @Override public void run() {
                    if (error != null) {
                        imageSaveCallback.onFail(error);
                    } else {
                        imageSaveCallback.onSuccess(uri);
                    }
                }
            });
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        cancel();
    }

    public void showImage(Uri uri) {
        showImage(Uri.EMPTY, uri);
    }

    public void showImage(final Uri thumbnail, final Uri uri) {
        showImage(thumbnail, uri, false);
    }

    public void showImage(final Uri thumbnail, final Uri uri,
            final boolean delayMainImageForTransition) {
        mThumbnail = thumbnail;
        mUri = uri;

        clearThumbnailAndProgressIndicator();

        mDelayMainImageForTransition = delayMainImageForTransition;
        if (mDelayMainImageForTransition) {
            BigImageViewer.prefetch(uri);
            mImageLoader.loadImage(hashCode(), thumbnail, mInternalCallback);
        } else {
            mImageLoader.loadImage(hashCode(), uri, mInternalCallback);
        }

        if (mFailureImageView != null) {
            mFailureImageView.setVisibility(GONE);
        }
    }

    public void loadMainImageNow() {
        mDelayMainImageForTransition = false;
        mImageLoader.loadImage(hashCode(), mUri, mInternalCallback);
    }

    public void cancel() {
        mImageLoader.cancel(hashCode());
    }

    public SubsamplingScaleImageView getSSIV() {
        return mSSIV;
    }

    @Override
    public void onCacheHit(final int imageType, final File image) {
        mCurrentImageFile = image;
        doShowImage(imageType, image, mDelayMainImageForTransition);

        if (mUserCallback != null) {
            mUserCallback.onCacheHit(imageType, image);
        }
    }

    @Override
    public void onCacheMiss(final int imageType, final File image) {
        mCurrentImageFile = image;
        doShowImage(imageType, image, mDelayMainImageForTransition);

        if (mUserCallback != null) {
            mUserCallback.onCacheMiss(imageType, image);
        }
    }

    @Override
    public void onStart() {
        // why show thumbnail in onStart? because we may not need download it from internet
        if (mThumbnail != Uri.EMPTY) {
            mThumbnailView = mViewFactory.createThumbnailView(getContext(), mThumbnailScaleType,
                    true);
            mViewFactory.loadThumbnailContent(mThumbnailView, mThumbnail);
            if (mThumbnailView != null) {
                addView(mThumbnailView, ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
            }
        }

        if (mProgressIndicator != null) {
            mProgressIndicatorView = mProgressIndicator.getView(BigImageView.this);
            mProgressIndicator.onStart();
            if (mProgressIndicatorView != null) {
                addView(mProgressIndicatorView);
            }
        }

        if (mUserCallback != null) {
            mUserCallback.onStart();
        }
    }

    @Override
    public void onProgress(final int progress) {
        if (mProgressIndicator != null) {
            mProgressIndicator.onProgress(progress);
        }
        if (mUserCallback != null) {
            mUserCallback.onProgress(progress);
        }
    }

    @Override
    public void onFinish() {
        doOnFinish();
        if (mUserCallback != null) {
            mUserCallback.onFinish();
        }
    }

    @Override
    public void onSuccess(final File image) {
        if (mUserCallback != null) {
            mUserCallback.onSuccess(image);
        }
    }

    @Override
    public void onFail(Exception error) {
        showFailImage();

        if (mUserCallback != null) {
            mUserCallback.onFail(error);
        }
    }

    @UiThread
    private void doOnFinish() {
        if (mOptimizeDisplay) {
            AnimationSet set = new AnimationSet(true);
            AlphaAnimation animation = new AlphaAnimation(1, 0);
            animation.setDuration(500);
            animation.setFillAfter(true);
            set.addAnimation(animation);
            if (mThumbnailView != null) {
                mThumbnailView.setAnimation(set);
            }
            if (mProgressIndicatorView != null) {
                mProgressIndicatorView.setAnimation(set);
            }

            if (mProgressIndicator != null) {
                mProgressIndicator.onFinish();
            }

            animation.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                }

                @Override
                public void onAnimationEnd(Animation animation) {
                    if (mThumbnailView != null) {
                        mThumbnailView.setVisibility(GONE);
                    }
                    if (mProgressIndicatorView != null) {
                        mProgressIndicatorView.setVisibility(GONE);
                    }

                    // fix:
                    // java.lang.NullPointerException:
                    // Attempt to read from field 'int android.view.View.mViewFlags'
                    // on a null object reference
                    // ref: https://stackoverflow.com/q/33242776/3077508
                    if (mThumbnailView != null || mProgressIndicatorView != null) {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                clearThumbnailAndProgressIndicator();
                            }
                        });
                    }
                }

                @Override
                public void onAnimationRepeat(Animation animation) {
                }
            });
        } else {
            if (mProgressIndicator != null) {
                mProgressIndicator.onFinish();
            }
            clearThumbnailAndProgressIndicator();
        }
    }

    @UiThread
    private void doShowImage(final int imageType, final File image,
            final boolean useThumbnailView) {
        if (useThumbnailView) {
            if (mThumbnailView != null) {
                removeView(mThumbnailView);
            }

            mThumbnailView = mViewFactory.createThumbnailView(getContext(), mThumbnailScaleType,
                    false);
            if (mThumbnailView != null) {
                addView(mThumbnailView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

                mThumbnailView.setOnClickListener(mOnClickListener);
                mThumbnailView.setOnLongClickListener(mOnLongClickListener);

                if (mThumbnailView instanceof ImageView) {
                    mViewFactory.loadThumbnailContent(mThumbnailView, image);

                    if (mImageShownCallback != null) {
                        mImageShownCallback.onThumbnailShown();
                    }
                }
            }
        } else {
            if (mMainView != null) {
                removeView(mMainView);
            }

            mMainView = mViewFactory.createMainView(getContext(), imageType, mInitScaleType);
            if (mMainView == null) {
                onFail(new RuntimeException("Image type not supported: "
                                            + ImageInfoExtractor.typeName(imageType)));
                return;
            }

            addView(mMainView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

            mMainView.setOnClickListener(mOnClickListener);
            mMainView.setOnLongClickListener(mOnLongClickListener);

            if (mMainView instanceof SubsamplingScaleImageView) {
                mSSIV = (SubsamplingScaleImageView) mMainView;

                mSSIV.setMinimumTileDpi(160);

                setOptimizeDisplay(mOptimizeDisplay);
                setInitScaleType(mInitScaleType);
            }

            if (mViewFactory.isAnimatedContent(imageType)) {
                mViewFactory.loadAnimatedContent(mMainView, imageType, image);
            } else {
                mViewFactory.loadSillContent(mMainView, Uri.fromFile(image));
            }

            if (mImageShownCallback != null) {
                mImageShownCallback.onMainImageShown();
            }
        }

        if (mFailureImageView != null) {
            mFailureImageView.setVisibility(GONE);
        }
    }

    @UiThread
    private void showFailImage() {
        // Failure image is not set
        if (mFailureImageView == null) {
            return;
        }
        if (mMainView != null) {
            removeView(mMainView);
        }

        mFailureImageView.setVisibility(VISIBLE);
        clearThumbnailAndProgressIndicator();
    }

    private void clearThumbnailAndProgressIndicator() {
        if (mThumbnailView != null) {
            removeView(mThumbnailView);
            mThumbnailView = null;
        }
        if (mProgressIndicatorView != null) {
            removeView(mProgressIndicatorView);
            mProgressIndicatorView = null;
        }
    }
}
