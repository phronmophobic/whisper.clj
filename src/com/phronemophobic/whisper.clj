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
                                AudioSystem
                                AudioFormat$Encoding)))

(defn transcribe [bs]
  (let [cparams (raw/whisper_context_default_params)
        
        ctx (raw/whisper_init_from_file_with_params "../whisper.clj/models/ggml-base.en.bin"
                                                    cparams)
        
        wparams (raw/whisper_full_default_params raw/WHISPER_SAMPLING_BEAM_SEARCH)
        buf (doto (Memory. (alength bs))
              (.write 0 bs 0 (alength bs)))
        n-samples (quot (alength bs)
                        4)]
    (raw/whisper_full ctx wparams buf n-samples)
    (let [n-segments (raw/whisper_full_n_segments ctx)
          transcription
          (str/join
           (eduction
            (map (fn [i]
                   (raw/whisper_full_get_segment_text ctx i)))
            (range n-segments)))]
      (raw/whisper_free ctx)
      transcription)))

(defn pcms16->pcmf32 [bs]
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


(defn bytes->floats [bs]
  (let [buf (doto (ByteBuffer/wrap bs)
              (.order (ByteOrder/nativeOrder)))
        fbuf (.asFloatBuffer buf)
        flts (float-array (quot (alength bs)
                                4))]
    
    (.get fbuf flts)
    flts))

(defn downsample [bs from-sample-rate to-sample-rate]
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

(defn default-mono-format []
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
       (.toByteArray out)))))

(defn record-and-transcribe []
  (let [get-audio (record-audio)]
    (fn []
      (transcribe (-> (get-audio)
                      pcms16->pcmf32
                      (downsample 44100 16000))))))

(comment

  (def get-text (record-and-transcribe))

  (get-text)

  ,)

#_(defn read-media [f]
  (let [bos (ByteArrayOutputStream.)
        frames (clj-media/frames
                (clj-media/file f)
                :audio
                {:format (clj-media/audio-format
                          {:channel-layout "mono"
                           :sample-rate 16000
                           :sample-format :sample-format/flt})})]
    (transduce
     
     (map (fn [frame]
            (let [bb (mm/byte-buffer frame)
                  bs (byte-array (.capacity bb))]
              (.get bb bs)
              (.write bos bs 0 (alength bs)))))
     (constantly nil)
     nil
     frames)
    (.toByteArray bos)))

(comment

  (let [f (io/file "/var/tmp/transcribe.mp3")
        format (clj-media/audio-format
                {:channel-layout "mono"
                 :sample-rate 44100
                 :sample-format :sample-format/flt
                 ;;:codec {:id 65557}
                 })]

    (clj-media/write!
     (clj-media/make-media
      format
      [(clj-media/make-frame
        {:format format
         :bytes (pcms16->pcmf32 bs)
         :time-base 44100
         :pts 0})])
     (.getCanonicalPath f)))

  (def frames
    (into []
          (let [in-format (clj-media/audio-format
                           {:channel-layout "mono"
                            :sample-rate 44100
                            :sample-format :sample-format/s16})]
            (clj-media/frames
             (clj-media/make-media
              in-format
              [(clj-media/make-frame
                {:format in-format
                 :bytes bs
                 :time-base 44100
                 :pts 0})])
             :audio
             {:format (clj-media/audio-format
                       {:channel-layout "mono"
                        :sample-rate 44100
                        :sample-format :sample-format/flt})}))))

  (def my-frame
    (-> frames
        first
        mm/byte-buffer
        ))
  (def my-bytes (byte-array (.capacity my-frame)))
  (.get my-frame my-bytes)

  (def my-floats (float-array (quot (.capacity my-frame) 4)))
  (.get (.asFloatBuffer my-frame) my-floats)


  
  
  ,)




