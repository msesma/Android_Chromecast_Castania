package eu.sesma.castania;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;

import eu.sesma.castania.castserver.CastServerService;

import static android.content.Context.WIFI_SERVICE;

public class WebServerController {

    private final Context context;

    public WebServerController(Context context) {
        this.context = context.getApplicationContext();
    }

    public void stopCastServer() {
        Intent castServerService = new Intent(context, CastServerService.class);
        context.stopService(castServerService);
    }

    private  void startCastServer(final String ip, final String rootDir) {
        Intent castServerService = new Intent(context, CastServerService.class);
        castServerService.putExtra(CastServerService.IP_ADDRESS, ip);
        castServerService.putExtra(CastServerService.ROOT_DIR, rootDir);
        context.startService(castServerService);
    }

    public MediaInfo getMediaInfo(String mediaName, boolean photoMode) {
        WifiManager wm = (WifiManager) context.getSystemService(WIFI_SERVICE);
        @SuppressWarnings("deprecation")
        String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());

        String filename = "";
        String rootDir = ".";
        int slash = mediaName.lastIndexOf('/');
        if (slash > 1) {
            filename = mediaName.substring(slash + 1);
            rootDir = mediaName.substring(0, slash);
        }
        startCastServer(ip, rootDir);

        String url = "http://" + ip + ":" + CastServerService.SERVER_PORT + "/" + filename;

        MediaMetadata mediaMetadata = photoMode ?
                new MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO) :
                new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);

        mediaMetadata.putString(MediaMetadata.KEY_TITLE, filename);

        return photoMode ?
                new MediaInfo.Builder(url)
                        .setContentType("image/jpeg")
                        .setStreamType(MediaInfo.STREAM_TYPE_NONE)
                        .setMetadata(mediaMetadata)
                        .build() :
                new MediaInfo.Builder(url)
                        .setContentType("video/mp4")
                        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                        .setMetadata(mediaMetadata)
//                    .setStreamDuration(selectedMedia.getDuration() * 1000)
                        .build();
    }
}
