/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar;

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.phone.StatusBar.DEBUG_MEDIA_FAKE_ARTWORK;
import static com.android.systemui.statusbar.phone.StatusBar.ENABLE_LOCKSCREEN_WALLPAPER;
import static com.android.systemui.statusbar.phone.StatusBar.SHOW_LOCKSCREEN_MEDIA_ARTWORK;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.AsyncTask;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.Interpolators;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.media.MediaData;
import com.android.systemui.media.MediaDataManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.dagger.StatusBarModule;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.LockscreenWallpaper;
import com.android.systemui.statusbar.phone.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ScrimState;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.Utils;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.tuner.TunerService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dagger.Lazy;

/**
 * Handles tasks and state related to media notifications. For example, there is a 'current' media
 * notification, which this class keeps track of.
 */
public class NotificationMediaManager implements Dumpable, TunerService.Tunable {
    private static final String TAG = "NotificationMediaManager";
    public static final boolean DEBUG_MEDIA = false;

    private static final String LOCKSCREEN_MEDIA_METADATA =
            Settings.Secure.LOCKSCREEN_MEDIA_METADATA;

    private static final String NOWPLAYING_SERVICE = "com.google.android.as";

    private final StatusBarStateController mStatusBarStateController
            = Dependency.get(StatusBarStateController.class);
    private final SysuiColorExtractor mColorExtractor = Dependency.get(SysuiColorExtractor.class);
    private final KeyguardStateController mKeyguardStateController = Dependency.get(
            KeyguardStateController.class);
    private final KeyguardBypassController mKeyguardBypassController;
    private static final HashSet<Integer> PAUSED_MEDIA_STATES = new HashSet<>();
    static {
        PAUSED_MEDIA_STATES.add(PlaybackState.STATE_NONE);
        PAUSED_MEDIA_STATES.add(PlaybackState.STATE_STOPPED);
        PAUSED_MEDIA_STATES.add(PlaybackState.STATE_PAUSED);
        PAUSED_MEDIA_STATES.add(PlaybackState.STATE_ERROR);
        PAUSED_MEDIA_STATES.add(PlaybackState.STATE_CONNECTING);
    }

    private final NotificationEntryManager mEntryManager;
    private final MediaDataManager mMediaDataManager;

    @Nullable
    private Lazy<NotificationShadeWindowController> mNotificationShadeWindowController;

    @Nullable
    private BiometricUnlockController mBiometricUnlockController;
    @Nullable
    private ScrimController mScrimController;
    @Nullable
    private LockscreenWallpaper mLockscreenWallpaper;

    private final DelayableExecutor mMainExecutor;

    private final Context mContext;
    private final MediaSessionManager mMediaSessionManager;
    private final ArrayList<MediaListener> mMediaListeners;
    private final Lazy<StatusBar> mStatusBarLazy;
    private final MediaArtworkProcessor mMediaArtworkProcessor;
    private final Set<AsyncTask<?, ?, ?>> mProcessArtworkTasks = new ArraySet<>();
    private float mLockscreenMediaBlur;

    protected NotificationPresenter mPresenter;
    private MediaController mMediaController;
    private String mMediaNotificationKey;
    private MediaMetadata mMediaMetadata;

    private String mNowPlayingNotificationKey;
    private String mNowPlayingTrack;

    private BackDropView mBackdrop;
    private ImageView mBackdropFront;
    private ImageView mBackdropBack;

    private boolean mShowCompactMediaSeekbar;
    private boolean mShowMediaMetadata;

    private final DeviceConfig.OnPropertiesChangedListener mPropertiesChangedListener =
            new DeviceConfig.OnPropertiesChangedListener() {
        @Override
        public void onPropertiesChanged(Properties properties) {
            for (String name : properties.getKeyset()) {
                if (SystemUiDeviceConfigFlags.COMPACT_MEDIA_SEEKBAR_ENABLED.equals(name)) {
                    String value = properties.getString(name, null);
                    if (DEBUG_MEDIA) {
                        Log.v(TAG, "DEBUG_MEDIA: compact media seekbar flag updated: " + value);
                    }
                    mShowCompactMediaSeekbar = "true".equals(value);
                }
            }
        }
    };

    private final MediaController.Callback mMediaListener = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            super.onPlaybackStateChanged(state);
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: onPlaybackStateChanged: " + state);
            }
            if (state != null) {
                if (!isPlaybackActive(state.getState())) {
                    clearCurrentMediaNotification();
                }
                findAndUpdateMediaNotifications();
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: onMetadataChanged: " + metadata);
            }
            mMediaArtworkProcessor.clearCache();
            mMediaMetadata = metadata;
            dispatchUpdateMediaMetaData(true /* changed */, true /* allowAnimation */);
        }
    };

    /**
     * Injected constructor. See {@link StatusBarModule}.
     */
    public NotificationMediaManager(
            Context context,
            Lazy<StatusBar> statusBarLazy,
            Lazy<NotificationShadeWindowController> notificationShadeWindowController,
            NotificationEntryManager notificationEntryManager,
            MediaArtworkProcessor mediaArtworkProcessor,
            KeyguardBypassController keyguardBypassController,
            @Main DelayableExecutor mainExecutor,
            DeviceConfigProxy deviceConfig,
            MediaDataManager mediaDataManager) {
        mContext = context;
        mMediaArtworkProcessor = mediaArtworkProcessor;
        mKeyguardBypassController = keyguardBypassController;
        mMediaListeners = new ArrayList<>();
        // TODO: use MediaSessionManager.SessionListener to hook us up to future updates
        // in session state
        mMediaSessionManager = (MediaSessionManager) mContext.getSystemService(
                Context.MEDIA_SESSION_SERVICE);
        // TODO: use KeyguardStateController#isOccluded to remove this dependency
        mStatusBarLazy = statusBarLazy;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mEntryManager = notificationEntryManager;
        mMainExecutor = mainExecutor;
        mMediaDataManager = mediaDataManager;

        notificationEntryManager.addNotificationEntryListener(new NotificationEntryListener() {

            @Override
            public void onPendingEntryAdded(NotificationEntry entry) {
                mediaDataManager.onNotificationAdded(entry.getKey(), entry.getSbn());
            }

            @Override
            public void onPreEntryUpdated(NotificationEntry entry) {
                mediaDataManager.onNotificationAdded(entry.getKey(), entry.getSbn());
            }

            @Override
            public void onEntryInflated(NotificationEntry entry) {
                findAndUpdateMediaNotifications();
            }

            @Override
            public void onEntryReinflated(NotificationEntry entry) {
                findAndUpdateMediaNotifications();
            }

            @Override
            public void onEntryRemoved(
                    NotificationEntry entry,
                    NotificationVisibility visibility,
                    boolean removedByUser,
                    int reason) {
                removeEntry(entry);
            }
        });

        // Pending entries are never inflated, and will never generate a call to onEntryRemoved().
        // This can happen when notifications are added and canceled before inflation. Add this
        // separate listener for cleanup, since media inflation occurs onPendingEntryAdded().
        notificationEntryManager.addCollectionListener(new NotifCollectionListener() {
            @Override
            public void onEntryCleanUp(@NonNull NotificationEntry entry) {
                removeEntry(entry);
            }
        });

        mMediaDataManager.addListener(new MediaDataManager.Listener() {
            @Override
            public void onMediaDataLoaded(@NonNull String key,
                    @Nullable String oldKey, @NonNull MediaData data) {
            }

            @Override
            public void onMediaDataRemoved(@NonNull String key) {
                NotificationEntry entry = mEntryManager.getPendingOrActiveNotif(key);
                if (entry != null) {
                    // TODO(b/160713608): "removing" this notification won't happen and
                    //  won't send the 'deleteIntent' if the notification is ongoing.
                    mEntryManager.performRemoveNotification(entry.getSbn(),
                            NotificationListenerService.REASON_CANCEL);
                }
            }
        });

        mShowCompactMediaSeekbar = "true".equals(
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                    SystemUiDeviceConfigFlags.COMPACT_MEDIA_SEEKBAR_ENABLED));

        deviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                mContext.getMainExecutor(),
                mPropertiesChangedListener);

        final TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.addTunable(this, LOCKSCREEN_MEDIA_METADATA);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (LOCKSCREEN_MEDIA_METADATA.equals(key)) {
            mShowMediaMetadata = TunerService.parseIntegerSwitch(newValue, false);
            dispatchUpdateMediaMetaData(false /* changed */, true /* allowAnimation */);
        }
    }

    private void removeEntry(NotificationEntry entry) {
        onNotificationRemoved(entry.getKey());
        mMediaDataManager.onNotificationRemoved(entry.getKey());
    }

    /**
     * Check if a state should be considered actively playing
     * @param state a PlaybackState
     * @return true if playing
     */
    public static boolean isPlayingState(int state) {
        return !PAUSED_MEDIA_STATES.contains(state);
    }

    public void setUpWithPresenter(NotificationPresenter presenter) {
        mPresenter = presenter;
    }

    public void onNotificationRemoved(String key) {
        if (key.equals(mMediaNotificationKey)) {
            clearCurrentMediaNotification();
            dispatchUpdateMediaMetaData(true /* changed */, true /* allowEnterAnimation */);
        }
        if (key.equals(mNowPlayingNotificationKey)) {
            mNowPlayingNotificationKey = null;
            dispatchUpdateMediaMetaData(true /* changed */, true /* allowEnterAnimation */);
        }
    }

    public String getMediaNotificationKey() {
        return mMediaNotificationKey;
    }

    public MediaMetadata getMediaMetadata() {
        return mMediaMetadata;
    }

    public boolean getShowCompactMediaSeekbar() {
        return mShowCompactMediaSeekbar;
    }

    public Icon getMediaIcon() {
        if (mMediaNotificationKey == null) {
            return null;
        }
        synchronized (mEntryManager) {
            NotificationEntry entry = mEntryManager
                    .getActiveNotificationUnfiltered(mMediaNotificationKey);
            if (entry == null || entry.getIcons().getShelfIcon() == null) {
                return null;
            }

            return entry.getIcons().getShelfIcon().getSourceIcon();
        }
    }

    public void addCallback(MediaListener callback) {
        mMediaListeners.add(callback);
        callback.onPrimaryMetadataOrStateChanged(mMediaMetadata,
                getMediaControllerPlaybackState(mMediaController));
    }

    public void removeCallback(MediaListener callback) {
        mMediaListeners.remove(callback);
    }

    public void findAndUpdateMediaNotifications() {
        boolean metaDataChanged = false;

        synchronized (mEntryManager) {
            Collection<NotificationEntry> allNotifications = mEntryManager.getAllNotifs();

            // Promote the media notification with a controller in 'playing' state, if any.
            NotificationEntry mediaNotification = null;
            MediaController controller = null;

            for (NotificationEntry entry : allNotifications) {
                if (entry.getSbn().getPackageName().toLowerCase().equals(NOWPLAYING_SERVICE)) {
                    mNowPlayingNotificationKey = entry.getSbn().getKey();
                    String notificationText = null;
                    final String title = entry.getSbn().getNotification()
                            .extras.getString(Notification.EXTRA_TITLE);
                    if (!TextUtils.isEmpty(title)) {
                        mNowPlayingTrack = title;
                    }
                    break;
                }
            }

            for (NotificationEntry entry : allNotifications) {
                if (entry.isMediaNotification()) {
                    final MediaSession.Token token =
                            entry.getSbn().getNotification().extras.getParcelable(
                                    Notification.EXTRA_MEDIA_SESSION);
                    if (token != null) {
                        MediaController aController = new MediaController(mContext, token);
                        if (PlaybackState.STATE_PLAYING ==
                                getMediaControllerPlaybackState(aController)) {
                            if (DEBUG_MEDIA) {
                                Log.v(TAG, "DEBUG_MEDIA: found mediastyle controller matching "
                                        + entry.getSbn().getKey());
                            }
                            mediaNotification = entry;
                            controller = aController;
                            break;
                        }
                    }
                }
            }
            if (mediaNotification == null) {
                // Still nothing? OK, let's just look for live media sessions and see if they match
                // one of our notifications. This will catch apps that aren't (yet!) using media
                // notifications.

                if (mMediaSessionManager != null) {
                    // TODO: Should this really be for all users?
                    final List<MediaController> sessions
                            = mMediaSessionManager.getActiveSessionsForUser(
                            null,
                            UserHandle.USER_ALL);

                    for (MediaController aController : sessions) {
                        // now to see if we have one like this
                        final String pkg = aController.getPackageName();

                        for (NotificationEntry entry : allNotifications) {
                            if (entry.getSbn().getPackageName().equals(pkg)) {
                                if (DEBUG_MEDIA) {
                                    Log.v(TAG, "DEBUG_MEDIA: found controller matching "
                                            + entry.getSbn().getKey());
                                }
                                controller = aController;
                                mediaNotification = entry;
                                break;
                            }
                        }
                    }
                }
            }

            if (controller != null && !sameSessions(mMediaController, controller)) {
                // We have a new media session
                clearCurrentMediaNotificationSession();
                mMediaController = controller;
                mMediaController.registerCallback(mMediaListener);
                mMediaMetadata = mMediaController.getMetadata();
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: insert listener, found new controller: "
                            + mMediaController + ", receive metadata: " + mMediaMetadata);
                }

                metaDataChanged = true;
            }

            if (mediaNotification != null
                    && !mediaNotification.getSbn().getKey().equals(mMediaNotificationKey)) {
                mMediaNotificationKey = mediaNotification.getSbn().getKey();
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: Found new media notification: key="
                            + mMediaNotificationKey);
                }
            }
        }

        if (metaDataChanged) {
            mEntryManager.updateNotifications("NotificationMediaManager - metaDataChanged");
        }

        dispatchUpdateMediaMetaData(metaDataChanged, true /* allowEnterAnimation */);
    }

    public String getNowPlayingTrack() {
        if (mNowPlayingNotificationKey == null) {
            mNowPlayingTrack = null;
        }
        return mNowPlayingTrack;
    }

    public void clearCurrentMediaNotification() {
        mMediaNotificationKey = null;
        clearCurrentMediaNotificationSession();
    }

    private void dispatchUpdateMediaMetaData(boolean changed, boolean allowEnterAnimation) {
        if (mPresenter != null) {
            mPresenter.updateMediaMetaData(changed, allowEnterAnimation);
        }
        @PlaybackState.State int state = getMediaControllerPlaybackState(mMediaController);
        ArrayList<MediaListener> callbacks = new ArrayList<>(mMediaListeners);
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).onPrimaryMetadataOrStateChanged(mMediaMetadata, state);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("    mMediaSessionManager=");
        pw.println(mMediaSessionManager);
        pw.print("    mMediaNotificationKey=");
        pw.println(mMediaNotificationKey);
        pw.print("    mMediaController=");
        pw.print(mMediaController);
        if (mMediaController != null) {
            pw.print(" state=" + mMediaController.getPlaybackState());
        }
        pw.println();
        pw.print("    mMediaMetadata=");
        pw.print(mMediaMetadata);
        if (mMediaMetadata != null) {
            pw.print(" title=" + mMediaMetadata.getText(MediaMetadata.METADATA_KEY_TITLE));
        }
        pw.println();
    }

    private boolean isPlaybackActive(int state) {
        return state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_ERROR
                && state != PlaybackState.STATE_NONE;
    }

    private boolean sameSessions(MediaController a, MediaController b) {
        if (a == b) {
            return true;
        }
        if (a == null) {
            return false;
        }
        return a.controlsSameSession(b);
    }

    private int getMediaControllerPlaybackState(MediaController controller) {
        if (controller != null) {
            final PlaybackState playbackState = controller.getPlaybackState();
            if (playbackState != null) {
                return playbackState.getState();
            }
        }
        return PlaybackState.STATE_NONE;
    }

    private void clearCurrentMediaNotificationSession() {
        mMediaArtworkProcessor.clearCache();
        mMediaMetadata = null;
        if (mMediaController != null) {
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: Disconnecting from old controller: "
                        + mMediaController.getPackageName());
            }
            mMediaController.unregisterCallback(mMediaListener);
        }
        mMediaController = null;
    }

    /**
     * Refresh or remove lockscreen artwork from media metadata or the lockscreen wallpaper.
     */
    public void updateMediaMetaData(boolean metaDataChanged, boolean allowEnterAnimation) {
        Trace.beginSection("StatusBar#updateMediaMetaData");
        if (!SHOW_LOCKSCREEN_MEDIA_ARTWORK) {
            Trace.endSection();
            return;
        }

        if (mBackdrop == null) {
            Trace.endSection();
            return; // called too early
        }

        boolean wakeAndUnlock = mBiometricUnlockController != null
            && mBiometricUnlockController.isWakeAndUnlock();
        if (mKeyguardStateController.isLaunchTransitionFadingAway() || wakeAndUnlock) {
            mBackdrop.setVisibility(View.INVISIBLE);
            Trace.endSection();
            return;
        }

        MediaMetadata mediaMetadata = getMediaMetadata();

        if (DEBUG_MEDIA) {
            Log.v(TAG, "DEBUG_MEDIA: updating album art for notification "
                    + getMediaNotificationKey()
                    + " metadata=" + mediaMetadata
                    + " metaDataChanged=" + metaDataChanged
                    + " state=" + mStatusBarStateController.getState());
        }

        Bitmap artworkBitmap = null;
        if (mediaMetadata != null && !mKeyguardBypassController.getBypassEnabled()) {
            artworkBitmap = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (artworkBitmap == null) {
                artworkBitmap = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            }
        }

        // Process artwork on a background thread and send the resulting bitmap to
        // finishUpdateMediaMetaData.
        if (metaDataChanged) {
            for (AsyncTask<?, ?, ?> task : mProcessArtworkTasks) {
                task.cancel(true);
            }
            mProcessArtworkTasks.clear();
        }
        if (artworkBitmap != null) {
            mProcessArtworkTasks.add(new ProcessArtworkTask(this, metaDataChanged,
                    allowEnterAnimation).execute(artworkBitmap));
        } else {
            finishUpdateMediaMetaData(metaDataChanged, allowEnterAnimation, null);
        }

        Trace.endSection();
    }

    private void finishUpdateMediaMetaData(boolean metaDataChanged, boolean allowEnterAnimation,
            @Nullable Bitmap bmp) {
        Drawable artworkDrawable = null;
        // set media artwork as lockscreen wallpaper if player is playing
        if (bmp != null && (mShowMediaMetadata || !ENABLE_LOCKSCREEN_WALLPAPER) &&
                PlaybackState.STATE_PLAYING == getMediaControllerPlaybackState(mMediaController)) {
            artworkDrawable = new BitmapDrawable(mBackdropBack.getResources(), bmp);
        }
        boolean hasMediaArtwork = artworkDrawable != null;
        boolean allowWhenShade = false;
        // if no media artwork, show normal lockscreen wallpaper
        if (ENABLE_LOCKSCREEN_WALLPAPER && artworkDrawable == null) {
            Bitmap lockWallpaper =
                    mLockscreenWallpaper != null ? mLockscreenWallpaper.getBitmap() : null;
            if (lockWallpaper != null) {
                artworkDrawable = new LockscreenWallpaper.WallpaperDrawable(
                        mBackdropBack.getResources(), lockWallpaper);
                // We're in the SHADE mode on the SIM screen - yet we still need to show
                // the lockscreen wallpaper in that mode.
                allowWhenShade = mStatusBarStateController.getState() == KEYGUARD;
            }
        }

        NotificationShadeWindowController windowController =
                mNotificationShadeWindowController.get();
        boolean hideBecauseOccluded = mStatusBarLazy.get().isOccluded();

        final boolean hasArtwork = artworkDrawable != null;
        mColorExtractor.setHasMediaArtwork(hasMediaArtwork);
        if (mScrimController != null) {
            mScrimController.setHasBackdrop(hasArtwork);
        }

        if ((hasArtwork || DEBUG_MEDIA_FAKE_ARTWORK)
                && (mStatusBarStateController.getState() != StatusBarState.SHADE || allowWhenShade)
                &&  mBiometricUnlockController != null && mBiometricUnlockController.getMode()
                        != BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
                && !hideBecauseOccluded) {
            // time to show some art!
            if (mBackdrop.getVisibility() != View.VISIBLE) {
                mBackdrop.setVisibility(View.VISIBLE);
                if (allowEnterAnimation) {
                    mBackdrop.setAlpha(0);
                    mBackdrop.animate().alpha(1f);
                } else {
                    mBackdrop.animate().cancel();
                    mBackdrop.setAlpha(1f);
                }
                if (windowController != null) {
                    windowController.setBackdropShowing(true);
                }
                metaDataChanged = true;
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: Fading in album artwork");
                }
            }
            if (metaDataChanged) {
                if (mBackdropBack.getDrawable() != null) {
                    Drawable drawable =
                            mBackdropBack.getDrawable().getConstantState()
                                    .newDrawable(mBackdropFront.getResources()).mutate();
                    mBackdropFront.setImageDrawable(drawable);
                    mBackdropFront.setAlpha(1f);
                    mBackdropFront.setVisibility(View.VISIBLE);
                } else {
                    mBackdropFront.setVisibility(View.INVISIBLE);
                }

                if (DEBUG_MEDIA_FAKE_ARTWORK) {
                    final int c = 0xFF000000 | (int)(Math.random() * 0xFFFFFF);
                    Log.v(TAG, String.format("DEBUG_MEDIA: setting new color: 0x%08x", c));
                    mBackdropBack.setBackgroundColor(0xFFFFFFFF);
                    mBackdropBack.setImageDrawable(new ColorDrawable(c));
                } else {
                    mBackdropBack.setImageDrawable(artworkDrawable);
                }

                if (mBackdropFront.getVisibility() == View.VISIBLE) {
                    if (DEBUG_MEDIA) {
                        Log.v(TAG, "DEBUG_MEDIA: Crossfading album artwork from "
                                + mBackdropFront.getDrawable()
                                + " to "
                                + mBackdropBack.getDrawable());
                    }
                    mBackdropFront.animate()
                            .setDuration(250)
                            .alpha(0f).withEndAction(mHideBackdropFront);
                }
            }
        } else {
            // need to hide the album art, either because we are unlocked, on AOD
            // or because the metadata isn't there to support it
            if (mBackdrop.getVisibility() != View.GONE) {
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: Fading out album artwork");
                }
                boolean cannotAnimateDoze = mStatusBarStateController.isDozing()
                        && !ScrimState.AOD.getAnimateChange();
                boolean needsBypassFading = mKeyguardStateController.isBypassFadingAnimation();
                if (((mBiometricUnlockController != null && mBiometricUnlockController.getMode()
                        == BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
                                || cannotAnimateDoze) && !needsBypassFading)
                        || hideBecauseOccluded) {

                    // We are unlocking directly - no animation!
                    mBackdrop.setVisibility(View.GONE);
                    mBackdropBack.setImageDrawable(null);
                    if (windowController != null) {
                        windowController.setBackdropShowing(false);
                    }
                } else {
                    if (windowController != null) {
                        windowController.setBackdropShowing(false);
                    }
                    mBackdrop.animate()
                            .alpha(0)
                            .setInterpolator(Interpolators.ACCELERATE_DECELERATE)
                            .setDuration(300)
                            .setStartDelay(0)
                            .withEndAction(() -> {
                                mBackdrop.setVisibility(View.GONE);
                                mBackdropFront.animate().cancel();
                                mBackdropBack.setImageDrawable(null);
                                mMainExecutor.execute(mHideBackdropFront);
                            });
                    if (mKeyguardStateController.isKeyguardFadingAway()) {
                        mBackdrop.animate()
                                .setDuration(
                                        mKeyguardStateController.getShortenedFadingAwayDuration())
                                .setStartDelay(
                                        mKeyguardStateController.getKeyguardFadingAwayDelay())
                                .setInterpolator(Interpolators.LINEAR)
                                .start();
                    }
                }
            }
        }
    }

    public void setup(BackDropView backdrop, ImageView backdropFront, ImageView backdropBack,
            ScrimController scrimController, LockscreenWallpaper lockscreenWallpaper) {
        mBackdrop = backdrop;
        mBackdropFront = backdropFront;
        mBackdropBack = backdropBack;
        mScrimController = scrimController;
        mLockscreenWallpaper = lockscreenWallpaper;
    }

    public void setBiometricUnlockController(BiometricUnlockController biometricUnlockController) {
        mBiometricUnlockController = biometricUnlockController;
    }

    /**
     * Hide the album artwork that is fading out and release its bitmap.
     */
    protected final Runnable mHideBackdropFront = new Runnable() {
        @Override
        public void run() {
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: removing fade layer");
            }
            mBackdropFront.setVisibility(View.INVISIBLE);
            mBackdropFront.animate().cancel();
            mBackdropFront.setImageDrawable(null);
        }
    };

    private Bitmap processArtwork(Bitmap artwork) {
        return mMediaArtworkProcessor.processArtwork(mContext, artwork, mLockscreenMediaBlur);
    }

    public void setLockScreenMediaBlurLevel() {
        mLockscreenMediaBlur = (float) Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_MEDIA_BLUR, 25,
                UserHandle.USER_CURRENT);
    }

    @MainThread
    private void removeTask(AsyncTask<?, ?, ?> task) {
        mProcessArtworkTasks.remove(task);
    }

    /**
     * {@link AsyncTask} to prepare album art for use as backdrop on lock screen.
     */
    private static final class ProcessArtworkTask extends AsyncTask<Bitmap, Void, Bitmap> {

        private final WeakReference<NotificationMediaManager> mManagerRef;
        private final boolean mMetaDataChanged;
        private final boolean mAllowEnterAnimation;

        ProcessArtworkTask(NotificationMediaManager manager, boolean changed,
                boolean allowAnimation) {
            mManagerRef = new WeakReference<>(manager);
            mMetaDataChanged = changed;
            mAllowEnterAnimation = allowAnimation;
        }

        @Override
        protected Bitmap doInBackground(Bitmap... bitmaps) {
            NotificationMediaManager manager = mManagerRef.get();
            if (manager == null || bitmaps.length == 0 || isCancelled()) {
                return null;
            }
            return manager.processArtwork(bitmaps[0]);
        }

        @Override
        protected void onPostExecute(@Nullable Bitmap result) {
            NotificationMediaManager manager = mManagerRef.get();
            if (manager != null && !isCancelled()) {
                manager.removeTask(this);
                manager.finishUpdateMediaMetaData(mMetaDataChanged, mAllowEnterAnimation, result);
            }
        }

        @Override
        protected void onCancelled(Bitmap result) {
            if (result != null) {
                result.recycle();
            }
            NotificationMediaManager manager = mManagerRef.get();
            if (manager != null) {
                manager.removeTask(this);
            }
        }
    }

    public interface MediaListener {
        /**
         * Called whenever there's new metadata or playback state.
         * @param metadata Current metadata.
         * @param state Current playback state
         * @see PlaybackState.State
         */
        default void onPrimaryMetadataOrStateChanged(MediaMetadata metadata,
                @PlaybackState.State int state) {}
    }
}
