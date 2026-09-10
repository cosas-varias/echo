Echo
====

Time travelling recorder for Android.
It is free/libre and gratis software.

Architecture
---

**SaidItFragment** the main view of the app, and the only one you normally look at: what is being
captured right now, the things you do with it, and every setting below that. It draws the live
half on a one second timer; **SettingsPanel** draws the settings half, and only when something
changes, so a field being typed into is never overwritten under the cursor. Traces, the
questionnaire and diagnostics are the only separate screens.

Settings are folded into sections whose headers carry a summary of how each group currently stands
— on or off, at what interval, listening for which word — so the whole configuration can be read
without unfolding anything, and each summary is refreshed on the timer because some of what it
reports (a revoked permission, GPS switched off) changes from outside the app.

**SaidItService** manages a high priority thread that records audio. The thread is a state machine that can be accessed by sending it tasks using Android's Handler (`audioHandler`).

**AudioMemory** (not thread-safe) manages the in-memory ring buffer of audio chunks.

**GpxTrack** (thread-safe) buffers location fixes and writes them out as a GPX track.

**ShakeCameraCapturer** watches the accelerometer and records a short silent clip from each camera
when the phone is shaken hard and then lets it settle, see *Asking for a clip* below. The front
camera can be left out.

**KeywordDetector** listens for a spoken word in the PCM the recorder has already read and sounds
an alarm when it hears it, see *Hearing a word* below.

**Survey** reads the branching questionnaire in `assets/survey.json`; **SurveyActivity** walks
through it and saves the answers.

Traces
---

Echo writes several kinds of trace into a shared `Echo` directory at the root of external storage,
falling back to app storage when that cannot be written to. Every save writes
`yyyyMMdd_HHmmss.wav`, named after the wall clock time of its first sample, with a `_2`, `_3`...
suffix when that second already has a file. With GPS logging on, the fixes taken while that
audio was captured are written as `yyyyMMdd_HHmmss.gpx` under exactly the same name, so a
recording and its track are easy to pair up.

Shake capture writes `yyyyMMdd_HHmmss_back.mp4` and `_front.mp4`, screenshots `_screen.jpg`, a
written note `_note.txt` and a filled-in survey `_survey.json`, all stamped the same way: the
leading timestamp is what places any of them on the timeline.

They are independent files throughout: each is listed, opened and deleted on its own, and
deleting one never touches the others. **Traces** lists every kind, reads notes and surveys on
the spot rather than handing them to another app, and still reads the old `Music/Echo` directory
so traces recorded before 2.2 stay visible. Older `_back.jpg` and `_front.jpg` stills from before
clips replaced them are still listed and still uploaded.

Sharing the microphone
---

Android hands the microphone input to one app at a time. When a call or a higher priority app
takes it, Echo's capture is not stopped: it is fed silence. So Echo asks for a capture that is
not privacy sensitive, which is what lets another app record alongside it where the platform
allows that at all, and watches `AudioManager.AudioRecordingCallback` to know when it is being
silenced rather than writing digital zeros and calling them a recording. The moment the input is
free again the `AudioRecord` is opened afresh, and a watchdog reopens it anyway if audio simply
stops arriving, so a microphone borrowed by another app never leaves Echo permanently deaf.

Asking for a clip
---

A clip is recorded when the phone is shaken hard and *then* settles for a second, within ten
seconds of the shake. Both halves are the point. An angle was the obvious trigger and the wrong
one: a phone carried upright in a pocket sits past any threshold for hours, so it filmed the
inside of the pocket all day. Shaking alone is not enough either, since walking and knocks produce
swings of their own — the shake is counted as four swings past the threshold within a second and a
half, and how hard those swings have to be is the one setting.

Both halves are read as a *change* in how the phone is moving rather than as movement outright,
which is what lets the gesture be used on the move. A slow average of the accelerometer is kept
as the background — near zero for a phone on a table, a couple of m/s² for one being walked with
— and frozen for as long as a gesture is under way. A swing counts when it rises past the
threshold *above* that background, so the same shake is asked for at a desk and on a walk. And
the second half is not stillness but a return: the movement of the last quarter of a second back
within 0.8 m/s² of the background it had before the shake, plus a further 0.6 of the background
itself, since a phone whose background is a walk does not sit at that background — every step is
a swing and no amount of smoothing hides them all. Standing still satisfies it; so does walking
on at the same pace. Measured absolutely, walking broke the gesture at both ends at once, since
the swings of a walk cross into the shake threshold while the phone never once goes still.

The background is frozen while a gesture is under way — a shake let into the average would raise
the bar it has to clear — with the two guards that freezing needs. It is never frozen in the
first two seconds after capture starts or after a gap in the samples, because the phone may well
have been in motion the whole time and one sample is a poor guess at what it is living with; for
those two seconds the background simply is the recent movement, and nothing is read as a gesture.
And it is never frozen for more than fifteen seconds at a stretch, because movement that sits
above the threshold and stays there — running, a phone on a machine — reads as one shake
re-arming after another and would otherwise hold the background frozen below what the phone is
doing forever, leaving it deaf to the real gesture.

Each clip is as wide as the hardware allows, because nothing about this gesture is aimed: there
is no preview, often no screen on, and the phone is pointed with a hand. So the widest lens of
however many a side has is the one used, chosen by the field of view its focal length and sensor
work out to; the request is zoomed all the way out, which on phones that hide their ultra wide
behind one logical camera — Pixels among them — is the only way to reach it at all; and the
recording is shaped like the sensor rather than like a screen, since asking a 4:3 sensor for 16:9
only throws away the top and bottom of the frame.

A gesture with no answer cannot be learnt, and this one has nothing to show for itself: no
preview, no shutter sound, and often no screen on. So the phone talks back through its vibrator,
three times per gesture, in shapes that can be told apart through a pocket:

| felt | means |
| --- | --- |
| two quick taps | a clip has started recording |
| one long buzz | a clip is on disk |
| two long buzzes | the gesture produced no file at all |

So a gesture is bracketed: one buzz when recording starts, one when it is over. The last two are
the same slot — a gesture ends with one or the other, never both — and a gesture that filled two
cameras still says each once. The amplitudes are asked for outright rather than
borrowed from the system's own haptics, which are tuned to be barely there.

Vibrating while reading the accelerometer needs care, because the motor shakes the very sensor the
gesture is read off: left alone, a buzz would be counted as swings towards the next shake. So
`buzz` puts the accelerometer aside for as long as the motor will run plus a moment to settle, and
those samples are dropped rather than read, since the only thing that moved the phone in that
window was the phone.

Stillness is what makes the gesture cheap to be wrong about. Whatever is worth filming is what the
phone is pointed at once it stops moving, and a phone being carried never stops moving: movement
has to stay under 0.8 m/s² for a full second, which a pocket on a walking person never manages.
The accelerometer's magnitude is compared against one g, so no orientation is needed: at rest it
reads near zero however the phone is lying, a few m/s² while it is carried, tens while it is shaken.

Hearing a word
---

Keyword detection is fed the samples the ring buffer has just taken, never a microphone of its
own: one app holds the input at a time, so a recogniser in another app (which is where
`SpeechRecognizer` runs) would silence the very recording it is meant to annotate. Recognition is
Vosk, offline, against a model in `assets/vosk-model`; the recogniser is built with a grammar of
just the words being listened for plus `[unk]`, which is what makes a small model usable for
spotting one word.

The model is tens of megabytes and is **not in this repository**. Without it the app builds and
runs, the settings screen says there is no model, and nothing is recognised. To switch the
feature on, unpack a Vosk model (e.g. `vosk-model-small-es-0.42`) so that
`SaidIt/src/main/assets/vosk-model/` holds its `am`, `conf`, `graph`... directories directly.

The recogniser is told the rate capture is actually running at and resamples internally, so a
sample rate change (which restarts capture, and with it the detector) is handled. Low power mode
is the exception worth knowing about: at 8 kHz there is simply less of the word left to
recognise, and detection gets noticeably worse.

Audio only reaches the detector when the capture loop reads it, and that loop otherwise sleeps
until the `AudioRecord` buffer is nearly full — twenty seconds at 48 kHz. That sleep is an
optimisation nothing else needed to give up, but an alarm answering a word most of a buffer later
is not an alarm, so while a keyword is being listened for, reads are capped at two seconds apart
and that gap is how late an alert can be.

Reading that often is not where the battery goes. Capture holds the audio path awake either way,
an ordinary recorder reads its microphone every few tens of milliseconds rather than every twenty
seconds, and each read is one memcpy. The cost is the recognition, and it is the same work however
the audio is sliced — so what is worth avoiding is recognising audio that cannot hold a word.
`KeywordDetector.feed` therefore drops a chunk whose loudest sample never reaches about -35 dBFS
before it is ever queued: in a quiet room nearly everything is dropped and listening costs close
to what recording costs, and in a room full of talking it costs what recognising talking costs.
