# MLBB Draft Assistant (v0.1)

Build: open this folder in Android Studio (Koala or newer), let Gradle sync, plug in your phone, Run.
Use: open the app -> "Allow overlay" -> "Start assistant" -> accept screen capture -> open MLBB draft.

Hero images go in app/src/main/assets/heroes/ (see README.txt there). Without them the app can
read the hero grid by text, but cannot recognise picks/bans.

Tune in Detector.kt: THRESHOLD (match strictness) and the layout fractions at the top.
Engine weights are in Engine.kt (DraftEngine.recommend).
