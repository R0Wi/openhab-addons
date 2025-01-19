#mvn clean install -DskipTests karaf:kar && \
#scp target/*.kar robin@testvm-ubuntu:~/openhab/addons
mvn spotless:apply && \
mvn clean install -DskipTests && \
scp target/org.openhab.binding.stiebelheatpump-4.2.1.jar robin@server.windey.home:/home/robin/docker/openhab_data/addons/manual-bundles/org.openhab.binding.stiebelheatpump-4.2.1.jar

# bundle:update "openHAB Add-ons :: Bundles :: stiebelheatpump Binding" file:/openhab/addons/manual-bundles/org.openhab.binding.stiebelheatpump-4.2.1.jar