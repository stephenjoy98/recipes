# Recipe Box

An Android app that turns cooking posts into recipes you can actually cook from. Share an
Instagram post or reel, a YouTube video or Short, a TikTok, or any recipe website to the app and
Gemini pulls out the ingredients, steps, prep/cook time, tags, category and estimated nutrition.

Built with Kotlin, Jetpack Compose and **Material Design 3 Expressive**, the newest version of
Google's Material 3. That means Material You dynamic color from your wallpaper on Android 12+,
expressive spring motion, shape-morphing buttons and loading indicators, the FAB menu, connected
button groups, wavy progress indicators, emphasized typography and expressive shapes.

## Features

- **Share to save.** In Instagram, YouTube, TikTok, Chrome and other apps, tap *Share → Save recipe*.
  You can also paste a link from the **+ Add recipe** button.
- **Gemini on your phone.** The app uses **Gemini Nano**, the model built into Android
  (through AICore and the ML Kit GenAI Prompt API). Recipes are read privately on the device.
  When Nano isn't available, or a video has no recipe in its description, the app can fall back to
  **cloud Gemini** through Firebase AI Logic. The cloud model can watch YouTube videos directly.
- **Recipe websites work without AI.** schema.org recipe cards are parsed exactly, and Gemini only
  fills in missing nutrition.
- **Ratio scaling.** Tap any ingredient amount ("3 eggs") and enter what you're using ("4"). Every
  other ingredient is rescaled by the same ratio (×1.33). You can also scale by servings (+/−) or
  with presets (½×, 1.5×, 2×, 3×). Fractions display as kitchen fractions (1⅓ cup), and you can
  save the scaled amounts as the recipe's new base amounts.
- **Tags & categories.** Chip-based tag editor with suggestions from your library, plus a Gemini
  "Suggest" button. Pick from default categories or create your own. Filter the library by
  category, any combination of tags, favorites, or total time (≤ 15 / 30 / 60 min).
- **Prep & cook time** with quick presets, shown on cards and in the detail view.
- **Nutrition** per serving or for the whole (scaled) batch. You can edit it or re-estimate it with Gemini.
- **Cooking mode helpers:** tick off ingredients and steps as you go, and share a recipe as text.
- **Export anywhere.** Export one recipe (from its ⋮ menu) or your whole library (the export
  icon on the home screen) as **JSON** (schema.org Recipe, which Mealie, Tandoor, Nextcloud
  Cookbook and others can import), **CSV** (spreadsheets), **PDF** (printable, with photos),
  **TXT**, or **Cooklang `.cook`** (several recipes are zipped). Save the file with the system
  file picker or send it through the share sheet. A scaled recipe can be exported with its scaled amounts.
- **Works on older Android.** The app supports Android 6.0 and later. Material You colors are
  used on Android 12+, with a matching built-in palette on older versions, and the splash screen
  and photo picker look the same everywhere. Gemini Nano is used only on devices that have it.
  Android Lint's `NewApi` check in CI fails the build on any API call that's too new for Android 6.
- **Google sign-in backup.** Sign in with Google and your library syncs two-way with Firestore.
  It backs up automatically after changes and twice a day, and deletions are synced too.

## Project layout

```
app/src/main/java/com/stephenjoy/recipes/
├── domain/      Recipe model, ratio scaling, quantity & ingredient parsing (pure Kotlin)
├── extract/     Link detection, page fetching (YouTube/Instagram/TikTok/web), schema.org parser,
│                and the import pipeline
├── ai/          Gemini Nano (ML Kit GenAI) + cloud Gemini (Firebase AI Logic), prompts, JSON parsing
├── data/        Room database, repositories, DataStore settings, image cache
├── backup/      Google sign-in (Credential Manager → Firebase Auth), Firestore sync, WorkManager job
└── ui/          Compose screens: home, import, detail (scaling), editor, settings
```

## Building

Requirements: a recent Android Studio with Android Gradle Plugin 9.1 support, JDK 17, Android SDK 37.

```bash
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests for scaling, parsing and AI-response handling
```

The app builds and runs **without any configuration**. In that mode it uses Gemini Nano on
supported devices, and Google sign-in, backup and cloud Gemini are turned off. To turn them on, set up Firebase:

### Turning on Google sign-in, backup and cloud Gemini (Firebase)

1. Create a project at <https://console.firebase.google.com> and add an Android app with
   package name `com.stephenjoy.recipes`. Add your debug/release **SHA-1** fingerprints
   (`./gradlew signingReport`).
2. **Authentication → Sign-in method → enable Google.**
3. **Firestore Database → Create database**, then publish the rules in [`firestore.rules`](firestore.rules).
4. **AI Logic → Get started** and choose the *Gemini Developer API* (needed for cloud Gemini).
5. Download `google-services.json` into `app/` (it is git-ignored) and rebuild.

For CI builds with Firebase, save the file base64-encoded as the `GOOGLE_SERVICES_JSON` repository
secret. The workflow writes it out before building.

### Gemini Nano

Gemini Nano runs on devices with Android AICore, such as the Pixel 9 series and newer, recent
Samsung Galaxy S devices, and other supported phones. Open **Settings → Gemini AI** to check
its status and download the model. On other devices the app uses cloud Gemini if Firebase is
configured, or lets you finish the recipe by hand.

## How link import works

1. **Fetch.** YouTube gets the title, description and captions. Instagram gets the caption from
   the public embed page (Open Graph data is the fallback). TikTok uses oEmbed. Websites get
   schema.org JSON-LD, Open Graph data and the article text.
2. **Read.** Gemini Nano structures the text into JSON (title, servings, times, category, tags,
   ingredients with numeric quantities, steps). If that fails, cloud Gemini tries with the text,
   then by watching the YouTube video.
3. **Nutrition.** Gemini estimates nutrition per serving from the ingredient list.
4. The recipe is saved, its thumbnail is cached locally (social CDN links expire), and you can review and edit it.

Private posts and pages behind a login can't be read. In that case the app saves a draft with the
link so you can fill in the rest.
