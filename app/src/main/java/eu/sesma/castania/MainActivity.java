package eu.sesma.castania;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.MediaRouteButton;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;

import butterknife.BindView;
import butterknife.ButterKnife;

public class MainActivity extends AppCompatActivity {
    // https://github.com/googlecast/CastCompanionLibrary-android/blob/master/CastCompanionLibrary.pdf
    // https://developers.google.com/cast/docs/sender_apps

    private static final String TAG = "Castania_Main";
    @BindView(R.id.bt_select_video)
    Button selectButtonVideo;
    @BindView(R.id.bt_select_photo)
    Button selectButtonPhoto;
    @BindView(R.id.bt_light)
    Button serverButtonLight;

    private MediaRouteButton mediaRouteButton;
    private static boolean photoMode;
    private final WebServerController webServerController = new WebServerController(this);

    private CastContext castContext;
    private CastSession castSession;
    private SessionManager sessionManager;
    private RemoteMediaClient remoteMediaClient;

    private RemoteMediaClient.Listener clientListener = new RemoteMediaClientListener() {
        @Override
        public void onStatusUpdated() {
            Intent intent = new Intent(MainActivity.this, ExpandedControlsActivity.class);
            startActivity(intent);
            remoteMediaClient.removeListener(this);
        }
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
        ActionBar actionBar = getSupportActionBar();
        actionBar.setBackgroundDrawable(new ColorDrawable(ContextCompat.getColor(this, android.R.color.transparent)));

        mediaRouteButton = (MediaRouteButton) findViewById(R.id.media_route_button);
        CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), mediaRouteButton);
        castContext = CastContext.getSharedInstance(this);
        sessionManager = castContext.getSessionManager();

        selectButtonVideo.setEnabled(false);
        selectButtonPhoto.setEnabled(false);
        stopCastServer();
    }

    @Override
    protected void onResume() {
        castSession = sessionManager.getCurrentCastSession();
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        castSession = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.main, menu);
        CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), menu, R.id.media_route_menu_item);
        return true;
    }

    public void onSelectButtonClick(final View v) {
        stopCastServer();
        Intent intent = new Intent(Intent.ACTION_PICK);
        photoMode = v != selectButtonVideo;
        String type = photoMode ? MediaStore.Video.Media.CONTENT_TYPE : MediaStore.Images.Media.CONTENT_TYPE;
        intent.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, type);
        startActivityForResult(intent, 0);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != 0) {
            @SuppressWarnings("deprecation")
            Cursor cursor = managedQuery(data.getData(), null, null, null, null);
            if (cursor.moveToFirst()) {
                try {
                    loadMedia(cursor.getString(1));
                } catch (Exception e) {
                    Log.d(TAG, "Unexpected exception " + e.getMessage());
                }
            }
        }
    }

    private void loadMedia(String mediaName) {
        MediaInfo mediaInfo = webServerController.getMediaInfo(mediaName, photoMode);

        remoteMediaClient = castSession.getRemoteMediaClient();
        remoteMediaClient.addListener(clientListener);
        remoteMediaClient.load(mediaInfo, true, 0);
    }

    private void stopCastServer() {
        webServerController.stopCastServer();
        serverButtonLight.setBackgroundColor(Color.RED);
    }
}
