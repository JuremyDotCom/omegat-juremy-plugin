# Juremy lookup trigger plugin for OmegaT

This plugins fakes being a machine translation provider, while it doesn't return any results.
Instead, it ships the search results to a separately connected Juremy UI.

## Setting up

### Installing the plugin for OmegaT

TODO describe where to get the zip and where to copy it.

### Setting up access

Enable the search push feature on your Juremy user settings. Copy the generated app token to
the dialog in OmegaT under `Options > Preferences > Machine Translation > Juremy > Configure`.

For best experience, enable automatically triggering Juremy using
`Options > Machine Translation > Automatically Fetch Translations`.

## Development

To develop the plugin, get JDK 14 or later on the path, and do `./gradlew build`.

To start OmegaT with the plugin, do `./gradlew runOmegaT`.

To clean up source code, run `./gradlew spotlessApply`.

This plugin was forked from the https://github.com/omegat-org/plugin-skeleton and the machinetranslation interface was
adapted from https://github.com/omegat-org/omegat/blob/master/machinetranslators/deepl/src/main/java/org/omegat/machinetranslators/deepl/DeepLTranslate.java.
