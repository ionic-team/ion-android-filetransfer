#!/usr/bin/env bash

ANDROID_PATH=../
LOG_OUTPUT=./tmp/publish-android.txt
THE_VERSION=`sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' ../pom.xml`

# Get latest io.ionic:portals XML version info
PUBLISHED_URL="https://repo1.maven.org/maven2/io/ionic/libs/ionfiletransfer-android/maven-metadata.xml"
PUBLISHED_DATA=$(curl -s $PUBLISHED_URL)
PUBLISHED_VERSION="$(perl -ne 'print and last if s/.*<latest>(.*)<\/latest>.*/\1/;' <<< $PUBLISHED_DATA)"

if [[ "$THE_VERSION" == "$PUBLISHED_VERSION" ]]; then
    printf %"s\n\n" "Duplicate: a published version exists for $THE_VERSION, skipping..."
else
    # Make log dir if doesnt exist
    mkdir -p ./tmp

    # Export ENV variable used by Gradle for Versioning
    export THE_VERSION
    export SHOULD_PUBLISH=true

    printf %"s\n" "Attempting to build and publish version $THE_VERSION"
    # Publish a release to the Maven repo
    "$ANDROID_PATH"/gradlew clean build publishReleasePublicationToSonatypeRepository closeAndReleaseSonatypeStagingRepository --no-daemon --max-workers 1 -b "$ANDROID_PATH"/build.gradle -Pandroid.useAndroidX=true > $LOG_OUTPUT 2>&1
    # Stage a version
    # "$ANDROID_PATH"/gradlew clean build publishReleasePublicationToSonatypeRepository --no-daemon --max-workers 1 -b "$ANDROID_PATH"/build.gradle -Pandroid.useAndroidX=true > $LOG_OUTPUT 2>&1

    echo $RESULT

    if grep --quiet "BUILD SUCCESSFUL" $LOG_OUTPUT; then
        printf %"s\n" "Success: Published to MavenCentral."
    else
        printf %"s\n" "Error publishing, check $LOG_OUTPUT for more info! Manually review and release from the Sonatype Repository Manager may be necessary https://s01.oss.sonatype.org/"
        cat $LOG_OUTPUT
        exit 1
    fi

fi