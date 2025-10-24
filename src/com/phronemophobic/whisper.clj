(ns com.phronemophobic.whisper
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.core.async :as async]            
            [com.phronemophobic.whisper.impl.raw :as raw]
            ;; [com.phronemophobic.clj-media.impl.audio :as audio]
            ;; [com.phronemophobic.clj-media.model :as mm]
            ;; [com.phronemophobic.clj-media :as clj-media]
            )
  (:import (java.nio ByteBuffer
                     ByteOrder)
           java.io.ByteArrayOutputStream
           com.sun.jna.Memory
           (javax.sound.sampled AudioFormat
                                DataLine$Info
                                TargetDataLine
                                AudioInputStream
                                AudioSystem
                                AudioFormat$Encoding)))

(defn transcribe+
  "Given a `model-path` to a whisper model and a byte array of
  sound samples, return a collection of maps with the following keys:
  `:text`: the transcribed text
  `:t0ms`: the start time of the segment in milliseconds
  `:t0ms`: the end time of the segment in milliseconds
  `:speaker-turn?`: The speaker turn of the segment. I'm not actually sure what this is and it doesn't seem to be supported by most models.

  The sounds samples must be in pcm signed 32 bit float format
  with a sampling rate of 16,000."
  [model-path ^bytes bs]
  (let [cparams (raw/whisper_context_default_params)
        
        ctx (raw/whisper_init_from_file_with_params model-path
                                                    cparams)
        _ (when (not ctx)
            (throw (ex-info "Whisper model could not be loaded"
                            {:model-path model-path})))
        
        wparams (raw/whisper_full_default_params raw/WHISPER_SAMPLING_BEAM_SEARCH)
        buf (doto (Memory. (alength bs))
              (.write 0 bs 0 (alength bs)))
        n-samples (quot (alength bs)
                        4)]
    (raw/whisper_full ctx wparams buf n-samples)
    (let [n-segments (raw/whisper_full_n_segments ctx)
          transcription
          (into []
                (map (fn [i]
                       {:text (raw/whisper_full_get_segment_text ctx i)
                        ;; //need to multiply times returned from whisper_full_get_segment_t{0,1}() by 10 to get milliseconds.
                        :t0ms (* 10 (raw/whisper_full_get_segment_t0 ctx i))
                        :t1ms (* 10 (raw/whisper_full_get_segment_t1 ctx i))
                        :speaker-turn? (not (zero? (raw/whisper_full_get_segment_speaker_turn_next ctx i)))
                        }))
                (range n-segments))]
      (raw/whisper_free ctx)
      transcription)))

(defn transcribe
  "Given a `model-path` to a whisper model and a byte array of
  sound samples, return a string with the transcribed text.

  The sounds samples must be in pcm signed 32 bit float format
  with a sampling rate of 16,000."
  [model-path ^bytes bs]
  (str/join
   (eduction
    (map :text)
    (transcribe+ model-path ^bytes bs))))

(defn pcms16->pcmf32
  "Convert a byte array of PCM signed shorts,
  return a byte array of pcm signed 32 bit floats.
  "
  [^bytes bs]
  (let [inbuf (doto (ByteBuffer/wrap bs)
                (.order (ByteOrder/nativeOrder)))
        buf (doto (ByteBuffer/allocate (* 2 (alength bs)))
              (.order (ByteOrder/nativeOrder)))
        fbuf (.asFloatBuffer buf)]
    (doseq [i (range (quot (alength bs) 2))]
      (.put fbuf
            (float (/ (.getShort inbuf)
                      Short/MAX_VALUE))))
    (.array buf)))


(defn bytes->floats [^bytes bs]
  (let [buf (doto (ByteBuffer/wrap bs)
              (.order (ByteOrder/nativeOrder)))
        fbuf (.asFloatBuffer buf)
        flts (float-array (quot (alength bs)
                                4))]
    
    (.get fbuf flts)
    flts))

(defn downsample
  "Convert the sound samples, `bs` from `from-sample-rate` to `to-sample-rate`.

  The sound samples must be pcmf32."
  [^bytes bs from-sample-rate to-sample-rate]
  (assert (< to-sample-rate from-sample-rate))
  (let [num-samples (dec
                     (long
                      (* (quot (alength bs) 4)
                         (/ to-sample-rate
                            from-sample-rate))))
        inbuf (doto (ByteBuffer/wrap bs)
                (.order (ByteOrder/nativeOrder)))
        outbuf (doto (ByteBuffer/allocate (* 4 num-samples))
                 (.order (ByteOrder/nativeOrder)))]
    (dotimes [i  num-samples
              ]
      (let [index (double (* i (/ from-sample-rate to-sample-rate)))
            i0 (long index)
            i1 (inc i0)
            f (- index (Math/floor index))]
        #_(.putFloat outbuf (* 4 i) (.getFloat inbuf (* 4 i0)))
        (.putFloat outbuf (* 4 i)
                   (+ (.getFloat inbuf (* 4 i0))
                      (* f (- (.getFloat inbuf (* 4 i1))
                              (.getFloat inbuf (* 4 i0))))))))
    (.array outbuf)))

(defn default-mono-format ^AudioFormat []
  (let [sample-rate 44100
        sample-size-in-bits 16
        channels 1
        frame-size (* channels (/ sample-size-in-bits 8))

        frame-rate 44100
        big-endian? false
        audio-format (AudioFormat. AudioFormat$Encoding/PCM_SIGNED
                                   sample-rate
                                   sample-size-in-bits
                                   channels
                                   frame-size
                                   frame-rate
                                   big-endian?)]
    audio-format))

(defn record-audio
  "Records audio from the default microphone.

  Returns a function that will stop recording and return the
  recorded audio as a byte array in mono, signed short format."
  ([]
   (record-audio {}))
  ([opts]
   (let [audio-format (or (:audio-format opts)
                          (default-mono-format))

         line-info (DataLine$Info. TargetDataLine audio-format)
         ^TargetDataLine
         line (AudioSystem/getLine line-info)

         _ (.open line audio-format)

         out (ByteArrayOutputStream.)
         buf-size (quot (.getBufferSize line) 5)
         ;; The number of bytes to be read must represent an integral number of sample frames
         buf-size (- buf-size (mod buf-size (-> (.getFormat line) (.getFrameSize))))
         
         buf (byte-array buf-size)
         running? (atom true)]

     (.start line)
     (async/thread
       (loop []
         (when @running?
           (let [bytes-read (.read line buf 0 (alength buf))]
             (.write out buf 0 bytes-read)
             (recur)))))

     (fn []
       (reset! running? false)
       (.stop line)
       (.drain line)
       (loop []
         (let [bytes-read (.read line buf 0 (alength buf))]
           (when (pos? bytes-read)
             (.write out buf 0 bytes-read)
             (recur))))
       (.close line)
       (.toByteArray out)))))

(defn record-and-transcribe
  "Starts recording audio from the default system microphone.

  Returns a function that will stop recording and return the
  transcribed text when called."
  [model-path]
  (let [get-audio (record-audio)]
    (fn []
      (transcribe model-path
                  (-> (get-audio)
                      pcms16->pcmf32
                      (downsample 44100 16000))))))




(defn ^:private convert-audio-format [audio-input-stream target-format]
  (let [decoded-stream (AudioSystem/getAudioInputStream ^AudioFormat target-format
                                                        ^AudioInputStream audio-input-stream)]
    decoded-stream))

(defn read-wav
  "Reads a wav file and returns a byte array in PCM signed shorts format."
  [file-path]
  (let [audio-input-stream (AudioSystem/getAudioInputStream
                            (java.io.File. ^String file-path))
        original-format (.getFormat audio-input-stream)
        target-format (default-mono-format)]
    (try
      (let [^AudioInputStream
            converted-stream (if (= original-format target-format)
                               audio-input-stream
                               (convert-audio-format audio-input-stream target-format))
            frame-length (.getFrameLength converted-stream)
            buffer-size (* frame-length (.getFrameSize target-format))
            byte-buffer (byte-array buffer-size)]
        (.read converted-stream byte-buffer)
        byte-buffer)
      (finally
        (.close audio-input-stream)))))

(defn transcribe-wav
  "Given the path to a whisper model and a wav file,
  return the transcribed text."
  [model-path wav-path]
  (transcribe model-path
              (-> (read-wav wav-path)
                  pcms16->pcmf32
                  (downsample 44100 16000))))

(defn transcribe-wav+
  "Given the path to a whisper model and a wav file,
  return the transcribed text as data."
  [model-path wav-path]
  (transcribe+ model-path
               (-> (read-wav wav-path)
                   pcms16->pcmf32
                   (downsample 44100 16000))))

(comment

  (transcribe-wav "models/ggml-base.en.bin"
                  "/var/tmp/firehose/the-language.wav")

  (def get-text (record-and-transcribe "models/ggml-base.en.bin"))

  (get-text)

  ,)






