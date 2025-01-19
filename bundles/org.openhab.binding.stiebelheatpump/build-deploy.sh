#mvn clean install -DskipTests karaf:kar && \
#scp target/*.kar robin@testvm-ubuntu:~/openhab/addons
mvn clean spotless:apply install -DskipTests && \
cp target/org.openhab.binding.stiebelheatpump-4.3.2.jar ~/Documents/docker/openhab/openhab_data/addons/org.openhab.binding.stiebelheatpump.jar && \
scp target/org.openhab.binding.stiebelheatpump-4.3.2.jar robin@server.windey.home:/home/robin/docker/openhab_data/addons/org.openhab.binding.stiebelheatpump.jar

# bundle:update "openHAB Add-ons :: Bundles :: stiebelheatpump Binding" file:/openhab/addons/manual-bundles/org.openhab.binding.stiebelheatpump.jar
