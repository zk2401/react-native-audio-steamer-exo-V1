package fm.indiecast.rnaudiostreamer;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.google.android.exoplayer.AspectRatioFrameLayout;
import com.google.android.exoplayer.DummyTrackRenderer;
import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecTrackRenderer;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.audio.AudioTrack;
import com.google.android.exoplayer.drm.ExoMediaDrm;
import com.google.android.exoplayer.drm.MediaDrmCallback;
import com.google.android.exoplayer.metadata.MetadataTrackRenderer;
import com.google.android.exoplayer.metadata.id3.Id3Frame;
import com.google.android.exoplayer.text.Cue;
import com.google.android.exoplayer.text.TextRenderer;
import com.google.android.exoplayer.upstream.BandwidthMeter;
import com.google.android.exoplayer.util.Util;

import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import fm.indiecast.rnaudiostreamer.trackrenderer.DashRenderersBuilder;
import fm.indiecast.rnaudiostreamer.trackrenderer.ExtractorRenderersBuilder;
import fm.indiecast.rnaudiostreamer.trackrenderer.HlsRenderersBuilder;
import fm.indiecast.rnaudiostreamer.trackrenderer.SmoothStreamingRenderersBuilder;

public class RNAudioStreamerModule extends ReactContextBaseJavaModule {
    private static final String TAG = "RNAudioStreamerModule";
    // Player
    private ExoPlayer player = null;
    private String status = "STOPPED";
    private ReactApplicationContext reactContext = null;

    public RNAudioStreamerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;

        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // Status
    private static final String PLAYING = "PLAYING";
    private static final String PAUSED = "PAUSED";
    private static final String STOPPED = "STOPPED";
    private static final String FINISHED = "FINISHED";
    private static final String BUFFERING = "BUFFERING";
    private static final String ERROR = "ERROR";


    @Override public String getName() {
        return "RNAudioStreamer";
    }

    @ReactMethod
    public void setUrl(String urlString) {

        if (player != null){
            player.stop();
            player = null;
            status = "STOPPED";
            this.sendStatusEvent();
        }

        this.player = ExoPlayer.Factory.newInstance(TrackRenderersBuilder.TRACK_RENDER_COUNT, 1000, 1000);
        this.player.addListener(internalEventListener);
        this.player.setPlayWhenReady(false);

        renderTracks(urlString);

    }

    private Handler mainHandler;

//    private String uri;

    private final InternalEventListener internalEventListener = new InternalEventListener();
    private final List<EventListener> eventListeners = new LinkedList<>();

    private TrackRenderersBuilder trackRenderersBuilder;
    private TrackRenderer videoTrackRenderer;
    private TrackRenderer audioTrackRenderer;


    private void renderTracks(String uri) {
        this.trackRenderersBuilder = createTrackRenderersBuilder(reactContext, uri);
        this.trackRenderersBuilder.build(new TrackRenderersBuilder.Callback() {
            @Override
            public void onFinish(TrackRenderer[] trackRenderers) {
                Log.d(TAG, "renderTracks...track renderers built");
                for (int i = 0; i < TrackRenderersBuilder.TRACK_RENDER_COUNT; i++) {
                    if (trackRenderers[i] == null) {
                        // Convert a null renderer to a dummy renderer.
                        trackRenderers[i] = new DummyTrackRenderer();
                    }
                }
                videoTrackRenderer = trackRenderers[TrackRenderersBuilder.TRACK_VIDEO_INDEX];
                audioTrackRenderer = trackRenderers[TrackRenderersBuilder.TRACK_AUDIO_INDEX];
                player.prepare(trackRenderers);

            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "renderTracks...failed to build track renderers", e);
                notifyError(e);
            }
        });
    }
    @ReactMethod public void play() {
        if(player != null) player.setPlayWhenReady(true);
    }

    @ReactMethod public void remove() {
        if (player != null){
            player.stop();
            player = null;
            status = "STOPPED";
            this.sendStatusEvent();
        }
    }
    @ReactMethod public void pause() {
        if(player != null) player.setPlayWhenReady(false);
    }

    @ReactMethod public void seekToTime(double time) {
        if(player != null) player.seekTo((long)time * 1000);
    }

    @ReactMethod public void currentTime(Callback callback) {
        if (player == null){
            callback.invoke(null,(double)0);
        }else{
            callback.invoke(null,(double)(player.getCurrentPosition()/1000));
        }
    }

    @ReactMethod public void status(Callback callback) {
        callback.invoke(null,status);
    }

    @ReactMethod public void duration(Callback callback) {
        if (player == null){
            callback.invoke(null,(double)0);
        }else{
            callback.invoke(null,(double)(player.getDuration()/1000));
        }
    }


    public void onLoadingChanged(boolean isLoading) {
        if (isLoading == true){
            status = BUFFERING;
            this.sendStatusEvent();
        }else if (this.player != null){
            if (this.player.getPlayWhenReady()) {
                status = PLAYING;
                this.sendStatusEvent();
            } else {
                status = PAUSED;
                this.sendStatusEvent();
            }
        }else{
            status = STOPPED;
            this.sendStatusEvent();
        }
    }


    private static String getDefaultUserAgent() {
        StringBuilder result = new StringBuilder(64);
        result.append("Dalvik/");
        result.append(System.getProperty("java.vm.version")); // such as 1.1.0
        result.append(" (Linux; U; Android ");

        String version = Build.VERSION.RELEASE; // "1.0" or "3.4b5"
        result.append(version.length() > 0 ? version : "1.0");

        // add the model for the release build
        if ("REL".equals(Build.VERSION.CODENAME)) {
            String model = Build.MODEL;
            if (model.length() > 0) {
                result.append("; ");
                result.append(model);
            }
        }
        String id = Build.ID; // "MASTER" or "M4-rc20"
        if (id.length() > 0) {
            result.append(" Build/");
            result.append(id);
        }
        result.append(")");
        return result.toString();
    }

    private void sendStatusEvent() {
        this.reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("RNAudioStreamerStatusChanged", status);
    }

    private TrackRenderersBuilder createTrackRenderersBuilder(Context context, String uriString) {
        Uri uri = null ;
        if(uriString.startsWith("http")){

        }else{
            uriString = "file://" + uriString ;
        }
        uri = Uri.parse(uriString);
        final int contentType = Util.inferContentType(uri.getLastPathSegment());
        final String userAgent = getDefaultUserAgent();

        switch (contentType) {
            case Util.TYPE_DASH:
                return new DashRenderersBuilder(context, userAgent, uriString, mainHandler,
                        mediaDrmCallback, internalEventListener, internalEventListener, internalEventListener, bandwidthMeterListener, player.getPlaybackLooper());
            case Util.TYPE_HLS:
                return new HlsRenderersBuilder(context, userAgent, uriString, mainHandler, internalEventListener, internalEventListener, internalEventListener, internalEventListener, bandwidthMeterListener);
            case Util.TYPE_SS:
                return new SmoothStreamingRenderersBuilder(context, userAgent, uriString, mainHandler, mediaDrmCallback, internalEventListener, internalEventListener, internalEventListener, bandwidthMeterListener, player.getPlaybackLooper());
            case Util.TYPE_OTHER:
                return new ExtractorRenderersBuilder(context, userAgent, uri, mainHandler,
                        internalEventListener, internalEventListener, internalEventListener, bandwidthMeterListener);
            default:
                throw new IllegalStateException("Unsupported content type: " + contentType);
        }
    }

    private class InternalEventListener implements MediaCodecVideoTrackRenderer.EventListener, MediaCodecAudioTrackRenderer.EventListener, TextRenderer, ExoPlayer.Listener, MetadataTrackRenderer.MetadataRenderer<List<Id3Frame>> {

        @Override
        public void onAudioTrackInitializationError(AudioTrack.InitializationException e) {

        }

        @Override
        public void onAudioTrackWriteError(AudioTrack.WriteException e) {
            notifyError(e);
        }

        @Override
        public void onAudioTrackUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {

        }

        @Override
        public void onDroppedFrames(int count, long elapsed) {
            Log.d(TAG, "onDroppedFrames...count=" + count + ", elapsed=" + elapsed);
        }

        @Override
        public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
        }

        @Override
        public void onDrawnToSurface(Surface surface) {
            Log.i(TAG, "onDrawnToSurface...");

        }

        @Override
        public void onDecoderInitializationError(MediaCodecTrackRenderer.DecoderInitializationException e) {
            notifyError(e);
        }

        @Override
        public void onCryptoError(MediaCodec.CryptoException e) {
            notifyError(e);
        }

        @Override
        public void onDecoderInitialized(String decoderName, long elapsedRealtimeMs, long initializationDurationMs) {

        }

        @Override
        public void onCues(List<Cue> cues) {
            notifyCues(cues);
        }

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            Log.d("onPlayerStateChanged", ""+playbackState);

            switch (playbackState) {
                case ExoPlayer.STATE_IDLE:
                    status = STOPPED;
                    sendStatusEvent();
                    break;
                case ExoPlayer.STATE_BUFFERING:
                    status = BUFFERING;
                    sendStatusEvent();
                    break;
                case ExoPlayer.STATE_READY:
                    if (player != null && player.getPlayWhenReady()) {
                        status = PLAYING;
                       sendStatusEvent();
                    } else {
                        status = PAUSED;
                        sendStatusEvent();
                    }
                    break;
                case ExoPlayer.STATE_ENDED:
                    status = FINISHED;
                    sendStatusEvent();
                    break;
            }
        }

        @Override
        public void onPlayWhenReadyCommitted() {

        }

        @Override
        public void onPlayerError(ExoPlaybackException error) {
            notifyError(error);
        }

        @Override
        public void onMetadata(List<Id3Frame> metadata) {

        }
    }

    private void notifyError(Exception e) {
        synchronized (eventListeners) {
            for (EventListener listener : eventListeners) {
                listener.onError(e);
            }
            status = ERROR;
            this.sendStatusEvent();
        }
    }


//    private void notifyPlayerStateChanged(boolean playWhenReady, int playbackState) {
//        if (playbackState == ExoPlayer.STATE_ENDED) {
//            ended = true;
//            if(loop) {
//                exoPlayer.seekTo(0);
//            }
//        } else {
//            ended = false;
//        }
//        synchronized (eventListeners) {
//            for (EventListener listener : eventListeners) {
//                listener.onPlayerStateChanged(playWhenReady, playbackState);
//            }
//        }
//    }

    private void notifyCues(List<Cue> cues) {
        synchronized (eventListeners) {
            for (EventListener listener : eventListeners) {
                listener.onCues(cues);
            }
        }
    }


    public interface EventListener {
        void onError(Exception e);

        /**
         * Invoked each time there's a change in the size of the video being rendered.
         *
         * @param width                    The video width in pixels.
         * @param height                   The video height in pixels.
         * @param unappliedRotationDegrees For videos that require a rotation, this is the clockwise
         *                                 rotation in degrees that the application should apply for the video for it to be rendered
         *                                 in the correct orientation. This value will always be zero on API levels 21 and above,
         *                                 since the renderer will apply all necessary rotations internally. On earlier API levels
         *                                 this is not possible. Applications that use {@link TextureView} can apply the rotation by
         *                                 calling {@link TextureView#setTransform}. Applications that do not expect to encounter
         *                                 rotated videos can safely ignore this parameter.
         * @param pixelWidthHeightRatio    The width to height ratio of each pixel. For the normal case
         *                                 of square pixels this will be equal to 1.0. Different values are indicative of anamorphic
         *                                 content.
         */
        void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio);

        void onPlayerStateChanged(boolean playWhenReady, int playbackState);

        /**
         * Invoked each time there is a change in the {@link Cue}s to be rendered.
         *
         * @param cues The {@link Cue}s to be rendered, or an empty list if no cues are to be rendered.
         */
        void onCues(List<Cue> cues);

        /**
         * Invoked each time there is a metadata associated with current playback time.
         *
         * @param metadata
         */
        void onMetadata(List<Id3Frame> metadata);
    }

    public static class BaseEventListener implements EventListener {

        @Override
        public void onError(Exception e) {

        }

        @Override
        public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {

        }

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {

        }

        @Override
        public void onCues(List<Cue> cues) {

        }

        @Override
        public void onMetadata(List<Id3Frame> metadata) {

        }
    }

    private final MediaDrmCallback mediaDrmCallback = new MediaDrmCallback() {
        @Override
        public byte[] executeProvisionRequest(UUID uuid, ExoMediaDrm.ProvisionRequest request) throws Exception {
            return new byte[0];
        }

        @Override
        public byte[] executeKeyRequest(UUID uuid, ExoMediaDrm.KeyRequest request) throws Exception {
            return new byte[0];
        }
    };

    private final BandwidthMeter.EventListener bandwidthMeterListener = new BandwidthMeter.EventListener() {
        @Override
        public void onBandwidthSample(int elapsedMs, long bytes, long bitrate) {
            Log.d(TAG, "onBandwidthSample...elapsedMs=" + elapsedMs + ", bitrate=" + bitrate);
        }
    };

}