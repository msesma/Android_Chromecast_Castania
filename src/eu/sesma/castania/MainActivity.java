
package eu.sesma.castania;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.MediaRouteActionProvider;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.support.v7.media.MediaRouter.RouteInfo;
import android.text.format.Formatter;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.RemoteMediaPlayer;
import com.google.android.gms.cast.RemoteMediaPlayer.MediaChannelResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;

import eu.sesma.castania.castserver.CastServerService;

import java.io.IOException;

public class MainActivity extends ActionBarActivity {
    // Main documentation site: https://developers.google.com/cast/
    // Main samples site: https://github.com/googlecast/
    // Google cast developer console: https://cast.google.com/publish/#/overview
    // Web server used in this sample: http://nanohttpd.com/

    private MediaRouter mRouter;
    private MediaRouter.Callback mCallback;
    private MediaRouteSelector mSelector;
    private CastDevice mSelectedDevice;
    private GoogleApiClient mApiClient;
    private ConnectionCallbacks mConnectionCallbacks;
    private OnConnectionFailedListener mConnectionFailedListener;
    private boolean mWaitingForReconnect = false;
    private static final String TAG = "Castania_Main";
    private Cast.Listener mCastListener;
    private boolean mApplicationStarted;
    private RemoteMediaPlayer mRemoteMediaPlayer;
    private boolean mIsPlaying = false;
    private static final int VOLUME_INCREMENT = 1;
    private static String mediaName = "";
    private Button selectButtonVideo, selectButtonPhoto, serverButtonLight;
    private static boolean photoMode;

    // App id obtained from google developer console app registration
    private final static String APP_ID = "4A687DE9";

    // Alternatively if no custom or personalized receiver is used:
    // private final static String APP_ID = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setBackgroundDrawable(new ColorDrawable(android.R.color.transparent));

        // Setup the router selector
        mRouter = MediaRouter.getInstance(getApplicationContext());
        // The MediaRouter needs to filter discovery for Cast devices that can launch the receiver application
        // associated with the sender app. In this case we search for ChromeCast devices
        mSelector = new MediaRouteSelector.Builder().addControlCategory(CastMediaControlIntent.categoryForCast(APP_ID))
                .build();
        mCallback = new MyMediaRouterCallback();

        selectButtonVideo = (Button) findViewById(R.id.bt_select_video);
        selectButtonPhoto = (Button) findViewById(R.id.bt_select_photo);
        serverButtonLight = (Button) findViewById(R.id.bt_light);
        // selectButtonVideo.setEnabled(false);
        // selectButtonPhoto.setEnabled(false);
        stopCastServer();
    }

    // Add the callback on resume to tell the media router what kinds of routes
    // the application is interested in so that it can try to discover suitable
    // ones.
    @Override
    protected void onResume() {
        super.onResume();
        mRouter.addCallback(mSelector, mCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
    }

    @Override
    protected void onPause() {
        // Remove the selector on stop to tell the media router that it no longer
        // needs to invest effort trying to discover routes of these kinds for now.
        if (isFinishing()) {
            mRouter.removeCallback(mCallback);
        }
        super.onPause();
    }

    @Override
    public void onDestroy() {
        teardown();
        stopCastServer();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.main, menu);
        MenuItem mediaRouteMenuItem = menu.findItem(R.id.media_route_menu_item);
        MediaRouteActionProvider mediaRouteActionProvider = (MediaRouteActionProvider) MenuItemCompat
                .getActionProvider(mediaRouteMenuItem);
        // Set the MediaRouteActionProvider selector for device discovery.
        mediaRouteActionProvider.setRouteSelector(mSelector);
        return true;
    }

    /**
     * Callback for MediaRouter events
     */
    private final class MyMediaRouterCallback extends MediaRouter.Callback {

        @Override
        public void onRouteSelected(final MediaRouter router, final RouteInfo info) {
            Log.d(TAG, "onRouteSelected");
            mSelectedDevice = CastDevice.getFromBundle(info.getExtras());

            // Once the application knows which Cast device the user selected,
            // the sender application can launch the
            // receiver application on that device.
            launchReceiver();
        }

        @Override
        public void onRouteUnselected(final MediaRouter router, final RouteInfo info) {
            Log.d(TAG, "onRouteUnselected: info=" + info);
            teardown();
            mSelectedDevice = null;
        }
    }

    /**
     * Start the receiver app
     */
    private void launchReceiver() {
        try {
            // The Cast.Listener callbacks are used to inform the sender
            // application about receiver application events:
            mCastListener = new Cast.Listener() {
                @Override
                public void onApplicationStatusChanged() {
                    if (mApiClient != null) {
                        Log.d(TAG, "onApplicationStatusChanged: " + Cast.CastApi.getApplicationStatus(mApiClient));
                    }
                }

                @Override
                public void onVolumeChanged() {
                    if (mApiClient != null) {
                        Log.d(TAG, "onVolumeChanged: " + Cast.CastApi.getVolume(mApiClient));
                    }
                }

                @Override
                public void onApplicationDisconnected(final int errorCode) {
                    Log.d(TAG, "application has stopped");
                    teardown();
                }

            };

            // Connect to Google Play services
            // The Cast SDK API’s are invoked using GoogleApiClient. A
            // GoogleApiClient instance is created using the
            // GoogleApiClient.Builder and requires various callbacks
            mConnectionCallbacks = new ConnectionCallbacks();
            mConnectionFailedListener = new ConnectionFailedListener();
            Cast.CastOptions.Builder apiOptionsBuilder = Cast.CastOptions.builder(mSelectedDevice, mCastListener);

            mApiClient = new GoogleApiClient.Builder(this).addApi(Cast.API, apiOptionsBuilder.build())
                    .addConnectionCallbacks(mConnectionCallbacks)
                    .addOnConnectionFailedListener(mConnectionFailedListener).build();

            mApiClient.connect();

        } catch (Exception e) {
            Log.e(TAG, "Failed launchReceiver", e);
        }
    }

    /**
     * Google Play services callbacks
     */
    private class ConnectionCallbacks implements GoogleApiClient.ConnectionCallbacks {
        // The application needs to declare GoogleApiClient.ConnectionCallbacks
        // and
        // GoogleApiClient.OnConnectionFailedListener callbacks to be informed
        // of the connection status. All of the
        // Google Play services callbacks run on the main UI thread.
        @Override
        public void onConnected(final Bundle connectionHint) {
            Log.d(TAG, "onConnected");

            if (mApiClient == null) {
                // We got disconnected while this runnable was pending
                // execution.
                return;
            }

            if (mWaitingForReconnect) {
                mWaitingForReconnect = false;

                // Check if the receiver app is still running
                if ((connectionHint != null) && connectionHint.getBoolean(Cast.EXTRA_APP_NO_LONGER_RUNNING)) {
                    Log.d(TAG, "App  is no longer running");
                    teardown();
                } else {
                    reattachMediaChannel();
                }
            } else {
                try {
                    // Once the connection is confirmed, the application can
                    // launch the receiver application by
                    // specifying the application ID
                    Cast.CastApi.launchApplication(mApiClient, APP_ID, false).setResultCallback(
                            new ResultCallback<Cast.ApplicationConnectionResult>() {
                                @Override
                                public void onResult(final Cast.ApplicationConnectionResult result) {
                                    Status status = result.getStatus();
                                    Log.d(TAG,
                                            "ApplicationConnectionResultCallback.onResult: statusCode"
                                                    + status.getStatusCode());
                                    if (status.isSuccess()) {
                                        ApplicationMetadata applicationMetadata = result.getApplicationMetadata();
                                        String sessionId = result.getSessionId();
                                        String applicationStatus = result.getApplicationStatus();
                                        boolean wasLaunched = result.getWasLaunched();

                                        Log.d(TAG, "application name: " + applicationMetadata.getName() + ", status: "
                                                + applicationStatus + ", sessionId: " + sessionId + ", wasLaunched: "
                                                + wasLaunched);
                                        mApplicationStarted = true;

                                        // Once the sender application is connected to
                                        // the receiver application, the
                                        // media channel can be created using
                                        // Cast.CastApi.setMessageReceivedCallbacks:
                                        attachMediaChannel();

                                    } else {
                                        Log.e(TAG, "application could not launch");
                                        teardown();
                                    }
                                }
                            });

                } catch (Exception e) {
                    Log.e(TAG, "Failed to launch application", e);
                }
            }
        }

        @Override
        public void onConnectionSuspended(final int cause) {
            // If GoogleApiClient.ConnectionCallbacks.onConnectionSuspended is
            // invoked when the client is temporarily in
            // a disconnected state, the application needs to track the state,
            // so that if
            // GoogleApiClient.ConnectionCallbacks.onConnected is subsequently
            // invoked when the connection is
            // established again, the application should be able to distinguish
            // this from the initial connected state.
            // It is important to re-create any channels when the connection is
            // re-established.
            mWaitingForReconnect = true;
            selectButtonVideo.setEnabled(false);
            selectButtonPhoto.setEnabled(false);
            stopCastServer();
        }
    }

    /**
     * Google Play services callbacks
     */
    private class ConnectionFailedListener implements GoogleApiClient.OnConnectionFailedListener {
        @Override
        public void onConnectionFailed(final ConnectionResult result) {
            Log.e(TAG, "onConnectionFailed ");
            teardown();
        }
    }

    /**
     * Tear down the connection to the receiver
     */
    private void teardown() {
        Log.d(TAG, "teardown");
        if (mApiClient != null) {
            if (mApplicationStarted) {
                if (mApiClient.isConnected()) {
                    Cast.CastApi.stopApplication(mApiClient);
                    // remove media channel:
                    detachMediaChannel();
                    mApiClient.disconnect();
                }
                mApplicationStarted = false;
            }
            mApiClient = null;
        }
        mSelectedDevice = null;
        mWaitingForReconnect = false;
    }

    /*
     * Media Channel ===================
     */

    // The Google Cast SDK supports a media channel to play media on a receiver
    // application. The media channel has a well-known namespace of
    // urn:x-cast:com.google.cast.media.
    // To use the media channel create an instance of RemoteMediaPlayer and set the
    // update listeners to receive media status updates:
    private void attachMediaChannel() {
        Log.d(TAG, "attachMedia()");
        if (null == mRemoteMediaPlayer) {
            mRemoteMediaPlayer = new RemoteMediaPlayer();

            mRemoteMediaPlayer.setOnStatusUpdatedListener(new RemoteMediaPlayer.OnStatusUpdatedListener() {

                @Override
                public void onStatusUpdated() {
                    MediaStatus mediaStatus = mRemoteMediaPlayer.getMediaStatus();
                    Log.d(TAG, "RemoteMediaPlayer::onStatusUpdated() is reached: " + String.valueOf(mediaStatus));
                    mIsPlaying = (mediaStatus.getPlayerState() == MediaStatus.PLAYER_STATE_PLAYING);
                }
            });

            mRemoteMediaPlayer.setOnMetadataUpdatedListener(new RemoteMediaPlayer.OnMetadataUpdatedListener() {
                @Override
                public void onMetadataUpdated() {
                    MediaInfo mediaInfo = mRemoteMediaPlayer.getMediaInfo();
                    Log.d(TAG, "RemoteMediaPlayer::onMetadataUpdated() is reached: " + String.valueOf(mediaInfo));

                    // MediaMetadata metadata = mediaInfo.getMetadata();
                }
            });

        }
        try {
            Log.d(TAG, "Registering MediaChannel namespace");
            Cast.CastApi.setMessageReceivedCallbacks(mApiClient, mRemoteMediaPlayer.getNamespace(), mRemoteMediaPlayer);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set up media channel", e);
        }

        // Call RemoteMediaPlayer.requestStatus() and wait for the
        // OnStatusUpdatedListener callback. This will update
        // the internal state of the RemoteMediaPlayer object with the current
        // state of the receiver, including the
        // current session ID.
        mRemoteMediaPlayer.requestStatus(mApiClient).setResultCallback(
                new ResultCallback<RemoteMediaPlayer.MediaChannelResult>() {
                    @Override
                    public void onResult(final MediaChannelResult result) {
                        if (!result.getStatus().isSuccess()) {
                            Log.e(TAG, "Failed to request status.");
                        } else {
                            selectButtonVideo.setEnabled(true);
                            selectButtonPhoto.setEnabled(true);
                        }
                    }
                });

    }

    private void reattachMediaChannel() {
        if (null != mRemoteMediaPlayer && null != mApiClient) {
            try {
                Log.d(TAG, "Registering MediaChannel namespace");
                Cast.CastApi.setMessageReceivedCallbacks(mApiClient, mRemoteMediaPlayer.getNamespace(),
                        mRemoteMediaPlayer);
            } catch (IOException e) {
                Log.e(TAG, "Failed to setup media channel", e);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to setup media channel", e);
            }
        }
    }

    private void detachMediaChannel() {
        Log.d(TAG, "trying to detach media channel");
        if (null != mRemoteMediaPlayer) {
            if (null != mRemoteMediaPlayer && null != Cast.CastApi) {
                try {
                    Cast.CastApi.removeMessageReceivedCallbacks(mApiClient, mRemoteMediaPlayer.getNamespace());
                } catch (Exception e) {
                    Log.e(TAG, "Failed to detach media channel", e);
                }
            }
            mRemoteMediaPlayer = null;
        }
    }

    // LOAD MEDIA
    // ========================
    public void onSelectButtonClick(final View v) {
        Intent ii = new Intent(Intent.ACTION_PICK);
        if (v == selectButtonVideo) {
            ii.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.CONTENT_TYPE);
            photoMode = false;
        } else {
            ii.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media.CONTENT_TYPE);
            photoMode = true;
        }
        startActivityForResult(ii, 0);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != 0) {
            @SuppressWarnings("deprecation")
            Cursor c = managedQuery(data.getData(), null, null, null, null);
            if (c.moveToFirst()) {
                try {
                    mediaName = c.getString(1);
                    loadMedia();
                } catch (Exception e) {
                    Log.d(TAG, "Unexpected exception " + e.getMessage());
                }
            }
        }

    }

    // To load media, the sender application needs to create a MediaInfo
    // instance using MediaInfo.Builder. The MediaInfo
    // instance is then used to load the media with the RemoteMediaPlayer
    // instance:
    private void loadMedia() {
        WifiManager wm = (WifiManager) getSystemService(WIFI_SERVICE);
        @SuppressWarnings("deprecation")
        String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        ((TextView) findViewById(R.id.tv_ip)).setText(ip);

        String filename = "";
        int slash = mediaName.lastIndexOf('/');
        if (slash > 1) {
            filename = mediaName.substring(slash + 1);
        }
        String rootDir = ".";
        if (slash > 1) {
            rootDir = mediaName.substring(0, slash);
        }
        startCastServer(ip, rootDir);

        String url = "http://" + ip + ":" + CastServerService.SERVER_PORT + "/" + filename;

        MediaMetadata mediaMetadata;
        if (photoMode) {
            mediaMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO);
        } else {
            mediaMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        }

        mediaMetadata.putString(MediaMetadata.KEY_TITLE, filename);
        MediaInfo mediaInfo;
        if (photoMode) {
            mediaInfo = new MediaInfo.Builder(url).setContentType("image/jpeg")
                    .setStreamType(MediaInfo.STREAM_TYPE_NONE).setMetadata(mediaMetadata).build();
        } else {
            mediaInfo = new MediaInfo.Builder(url).setContentType("video/mp4")
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setMetadata(mediaMetadata).build();
        }
        try {
            mRemoteMediaPlayer.load(mApiClient, mediaInfo, true).setResultCallback(
                    new ResultCallback<RemoteMediaPlayer.MediaChannelResult>() {
                        @Override
                        public void onResult(final MediaChannelResult result) {
                            if (result.getStatus().isSuccess()) {
                                Log.d(TAG, "Media loaded successfully");
                            }
                        }
                    });
        } catch (IllegalStateException e) {
            Log.e(TAG, "Problem occurred with media during loading", e);
        } catch (Exception e) {
            Log.e(TAG, "Problem opening media during loading", e);
        }
    }

    /*
     * Soft and HW buttons ===================
     */

    public void onClickPlay(final View v) {
        if (mRemoteMediaPlayer == null || mApiClient == null) {
            return;
        }
        if (mIsPlaying) {
            mRemoteMediaPlayer.pause(mApiClient);
            ((ImageButton) v).setImageDrawable(getResources().getDrawable(R.drawable.play));
        } else {
            mRemoteMediaPlayer.play(mApiClient);
            ((ImageButton) v).setImageDrawable(getResources().getDrawable(R.drawable.pause));
        }

    }

    public void onClickStop(final View v) {
        if (mRemoteMediaPlayer == null || mApiClient == null) {
            return;
        }
        mRemoteMediaPlayer.stop(mApiClient);
        ((ImageButton) findViewById(R.id.bt_play)).setImageDrawable(getResources().getDrawable(R.drawable.play));
    }

    public void onClickFfw(final View v) {
        if (mRemoteMediaPlayer == null || mApiClient == null) {
            return;
        }
        Long position = mRemoteMediaPlayer.getApproximateStreamPosition() + mRemoteMediaPlayer.getStreamDuration() / 10;
        if (position > mRemoteMediaPlayer.getStreamDuration()) {
            position = mRemoteMediaPlayer.getStreamDuration();
        }
        mRemoteMediaPlayer.seek(mApiClient, position);

    }

    public void onClickFrw(final View v) {
        if (mRemoteMediaPlayer == null || mApiClient == null) {
            return;
        }
        Long position = mRemoteMediaPlayer.getApproximateStreamPosition() - mRemoteMediaPlayer.getStreamDuration() / 10;
        if (position < 0) {
            position = 0l;
        }
        mRemoteMediaPlayer.seek(mApiClient, position);
    }

    @Override
    public boolean dispatchKeyEvent(final KeyEvent event) {
        int action = event.getAction();
        int keyCode = event.getKeyCode();
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (action == KeyEvent.ACTION_DOWN) {
                    if (mRemoteMediaPlayer != null) {
                        double currentVolume = Cast.CastApi.getVolume(mApiClient);
                        if (currentVolume < 1.0) {
                            try {
                                Cast.CastApi.setVolume(mApiClient, Math.min(currentVolume + VOLUME_INCREMENT, 1.0));
                            } catch (Exception e) {
                                Log.e(TAG, "unable to set volume", e);
                            }
                        }
                    } else {
                        Log.e(TAG, "dispatchKeyEvent - volume up");
                    }
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (action == KeyEvent.ACTION_DOWN) {
                    if (mRemoteMediaPlayer != null) {
                        double currentVolume = Cast.CastApi.getVolume(mApiClient);
                        if (currentVolume > 0.0) {
                            try {
                                Cast.CastApi.setVolume(mApiClient, Math.max(currentVolume - VOLUME_INCREMENT, 0.0));
                            } catch (Exception e) {
                                Log.e(TAG, "unable to set volume", e);
                            }
                        }
                    } else {
                        Log.e(TAG, "dispatchKeyEvent - volume down");
                    }
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    // WEB SERVER
    private void startCastServer(final String ip, final String rootDir) {
        Intent castServerService = new Intent(this, CastServerService.class);
        castServerService.putExtra(CastServerService.IP_ADDRESS, ip);
        castServerService.putExtra(CastServerService.ROOT_DIR, rootDir);
        startService(castServerService);
        serverButtonLight.setBackgroundColor(Color.GREEN);
    }

    private void stopCastServer() {
        Intent castServerService = new Intent(this, CastServerService.class);
        stopService(castServerService);
        serverButtonLight.setBackgroundColor(Color.RED);
    }

}
