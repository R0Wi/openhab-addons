#mvn clean install -DskipTests karaf:kar && \
#scp target/*.kar robin@testvm-ubuntu:~/openhab/addons
mvn clean spotless:apply install -DskipTests && \
cp target/org.openhab.binding.stiebelheatpump-5.1.1-SNAPSHOT.jar ~/Documents/docker/openhab/openhab_data/addons/org.openhab.binding.stiebelheatpump.jar && \
scp target/org.openhab.binding.stiebelheatpump-5.1.1-SNAPSHOT.jar robin@server.windey.home:/home/robin/docker/openhab_data/addons/org.openhab.binding.stiebelheatpump.jar

# bundle:update "openHAB Add-ons :: Bundles :: stiebelheatpump Binding" file:/openhab/addons/manual-bundles/org.openhab.binding.stiebelheatpump.jar

# relevant git commits:
git cherry-pick d255f9e9f927c91241cf670e5b6b9b49c42c737a~1..9e4d22fb0b95deaa7fb717c3d9e788fc7cf20ca9
# notes:
#   - head might change
#   - sometimes it's better to use OH branch as base (for example 5.1.x) instead of tag to get rid of merge conflicts