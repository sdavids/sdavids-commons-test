#!/usr/bin/env bash
echo -n "PGP Private Key Password: "
read -s gpgpassword
echo
./gradlew -Prelease -Psigning.password="${gpgpassword}" clean build generatePomFileForMavenJavaPublication bintrayUpload
