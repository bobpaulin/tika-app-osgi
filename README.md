# Tika App OSGi

A tika app that runs the tika bundles within an embedded OSGi environment.

## Why build a new Tika App?
Tika is a collection of many third party libraries that are used to parse different types of content.  These libraries have many dependencies and transitive dependencies which is a recipe for JAR hell.  Upgrading parser libraries can be very painful since it often involved a complex dance of resolving conflicts between new and old dependencies.  Within the Tika 2.0 branch the parser libraries were separated into modules that have been wrapped in to OSGi bundles.  Putting the modules in an OSGi environment allows each module to have it's own classloader which isolates the dependencies and transitive dependencies at a module level.  The new app embraces this approach with the goal of making library upgrades less of a chore so they can happen more frequently and with less risk.  A secondary goal is to adopt some of the features that come along with running the app in an OSGi enviroment such as support for plugins and leveraging a more robust command shell.

## Command Bundle
This bundle contains much of the tika app code but with some modifications to allow it to function with all bundles.  The command bundle is responsible for translating the CLI commands to the application in a backwards compatible manner to the existing Tika App.

## Plugins
Additional Parsers, Detectors, EncodingDetectors, and Language Detectors may be added by placing OSGi bundles in a directory called plugins at the same level as the Tika App JAR.

Example

     tika-app.jar
       plugins
         parser-bundle.jar

An example of a plugin can be find in the examples folder of this project.  The example plugin includes the bare minimum needed to add a parser to the app.  Specifically that is the Parser, Activator, and Java Serivce file located in META-INF/services.

## Debugging the OSGi container
The Gogo command shell is available over telnet on port 1234.  The port will auto-increment if 1234 is unavailable.

Additionally when making changes to the app be sure to delete the felix-cache folder that's created on the same level as the app jar file.  Any plugins installed or tika bundles will be cached in the folder so you may not see your changes if you don't delete it.  Pass the System Property

     -Dorg.osgi.framework.storage.clean=onFirstInit

That will force the cache to be cleared when the app is restarted.

## Fork Parser
The fork parser operates different than the original tika app.  The forking causes a full version of the Tika App to spin up with separate command line arguments to run the app in a new process.  There is no separate JAR packaging or serialization.