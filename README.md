# whisper.clj

Clojure wrapper for [whisper.cpp](https://github.com/ggerganov/whisper.cpp). Provides local audio transcription.

## Usage

### Dependency

```clojure
com.phronemophobic/whisper-clj {:mvn/version "1.2"}
;; native dependencies
com.phronemophobic.cljonda/whisper-cpp-darwin-aarch64 {:mvn/version "c96906d84dd6a1c40ea797ad542df3a0c47307a3"}
com.phronemophobic.cljonda/whisper-cpp-darwin-x86-64 {:mvn/version "c96906d84dd6a1c40ea797ad542df3a0c47307a3"}
com.phronemophobic.cljonda/whisper-cpp-linux-x86-64 {:mvn/version "c96906d84dd6a1c40ea797ad542df3a0c47307a3"}
```

### 1. Download whisper.cpp

```bash
git clone https://github.com/ggerganov/whisper.cpp.git
```

### 2. Build whisper.cpp

_This step can be skipped if the clojars native dependencies are used._

```bash
cmake -B build -DBUILD_SHARED_LIBS=1 -DGGML_USE_ACCELERATE=1 -DGGML_USE_METAL=1 -DGGML_METAL_EMBED_LIBRARY=1
cmake --build build -j --config Release
```

### 3. Download model

```bash
# in whisper.cpp
bash ./models/download-ggml-model.sh base.en
```

### 4. Copy model to whisper.clj

```bash
# in whisper.clj
mkdir models
cp ../whisper.cpp/models/ggml-base.en.bin models/
```

### 5. Setup alias

_This step can be skipped if the clojars native dependencies are used._

Create alias to point to local build

```clojure
:whisper
{:jvm-opts ["-Djna.library.path=../whisper.cpp/build/src/"]}
```

### 6. Transcribe Audio

####  Transcribe Recorded Audio

```clojure

(require '[com.phronemophobic.whisper :as whisper])

;; start recording from the default system microphone
(def get-text (whisper/record-and-transcribe "models/ggml-base.en.bin"))

;; stop recording and return transcription
(def transcription (get-text))
```

If it didn't work, you may have to check your OSX permissions and allow microphone access to Terminal.

##### Transcribe Wav

```clojure
(require '[com.phronemophobic.whisper :as whisper])

;; reads `my-audio.wav` and returns
;; the transcribed text.
(def transcription (whisper/transcribe-wav
                    "models/ggml-base.en.bin"
                    "my-audio.wav"))
```

## Examples

[whistle](https://gitlab.com/devcarbon/whistle) - a voice-to-text typing tool for Linux and MacOS

## License

The MIT License (MIT)

Copyright © 2024 Adrian Smith

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.



