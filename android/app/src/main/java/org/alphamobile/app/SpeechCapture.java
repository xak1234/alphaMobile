package org.alphamobile.app;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;

/** Call-only microphone capture with platform echo cancellation and streaming recognition. */
final class SpeechCapture implements AutoCloseable {
    private final AudioRecord recorder;
    private final Recognizer recognizer;
    private final RecognitionListener listener;
    private final Handler main;
    private AcousticEchoCanceler echo;
    private NoiseSuppressor noise;
    private volatile boolean running;
    private boolean started;
    final boolean echoCancellation;

    @SuppressLint("MissingPermission")
    SpeechCapture(Model model, RecognitionListener listener, Handler main) throws Exception {
        this.listener=listener;this.main=main;
        int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minimum<=0)throw new IllegalStateException("16 kHz microphone input is unavailable");
        recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,16000,
            AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,6400));
        Recognizer created=null;
        try {
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone failed to initialise");
            created=new Recognizer(model,16000);created.setEndpointerDelays(5.0f,0.6f,30.0f);
            boolean enabled=false;
            try{if(AcousticEchoCanceler.isAvailable())echo=AcousticEchoCanceler.create(recorder.getAudioSessionId());enabled=echo!=null&&echo.setEnabled(true)==AudioEffect.SUCCESS&&echo.getEnabled();}catch(IllegalArgumentException|UnsupportedOperationException ignored){}
            echoCancellation=enabled;
            try{if(NoiseSuppressor.isAvailable()){noise=NoiseSuppressor.create(recorder.getAudioSessionId());if(noise!=null)noise.setEnabled(true);}}catch(IllegalArgumentException|UnsupportedOperationException ignored){}
            recognizer=created;
        }catch(Exception error){if(created!=null)created.close();if(echo!=null)echo.release();if(noise!=null)noise.release();recorder.release();throw error;}
    }
    void start(){recorder.startRecording();running=true;started=true;new Thread(this::capture,"alphaMobile-microphone").start();}
    private void capture(){
        short[] buffer=new short[320];int chunks=0;
        try {
            while(running){
                int count=recorder.read(buffer,0,buffer.length);
                if(!running)break;
                if(count<0)throw new IllegalStateException("Microphone read failed: "+count);
                if(count==0)continue;
                if(recognizer.acceptWaveForm(buffer,count)){
                    String result=recognizer.getResult();main.post(()->{if(running)listener.onResult(result);});
                }else if(++chunks%4==0){
                    String partial=recognizer.getPartialResult();main.post(()->{if(running)listener.onPartialResult(partial);});
                }
            }
        }catch(Exception error){if(running)main.post(()->{if(running)listener.onError(error);});}
        finally{release();}
    }
    private void release(){try{recorder.stop();}catch(IllegalStateException ignored){}recorder.release();if(echo!=null)echo.release();if(noise!=null)noise.release();recognizer.close();}
    @Override public void close(){running=false;if(started){try{recorder.stop();}catch(IllegalStateException ignored){}}else release();}
}
