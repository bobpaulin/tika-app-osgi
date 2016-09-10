/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tika.main;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.felix.framework.util.Util;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.startlevel.FrameworkStartLevel;

/**
 * <p>
 * This class is the default way to instantiate and execute the framework. It is
 * not intended to be the only way to instantiate and execute the framework;
 * rather, it is one example of how to do so. When embedding the framework in a
 * host application, this class can serve as a simple guide of how to do so. It
 * may even be worthwhile to reuse some of its property handling capabilities.
 * </p>
 **/
public class Main {
    /**
     * This argument indicates that the batch CLI should be activated.
     */
    public static final String BATCH_MODE_SWITCH = "--batch-mode";
    /**
     * The property name used to specify whether the launcher should install a
     * shutdown hook.
     **/
    public static final String SHUTDOWN_HOOK_PROP = "felix.shutdown.hook";
    /**
     * The property name used to specify an URL to the system property file.
     **/
    public static final String SYSTEM_PROPERTIES_PROP = "felix.system.properties";
    /**
     * The default name used for the system properties file.
     **/
    public static final String SYSTEM_PROPERTIES_FILE_VALUE = "system.properties";
    /**
     * The property name used to specify an URL to the configuration property
     * file to be used for the created the framework instance.
     **/
    public static final String CONFIG_PROPERTIES_PROP = "felix.config.properties";
    /**
     * The default name used for the configuration properties file.
     **/
    public static final String CONFIG_PROPERTIES_FILE_VALUE = "config.properties";
    /**
     * Name of the configuration directory.
     */
    public static final String CONFIG_DIRECTORY = "conf";

    private static Framework m_fwk = null;

    public static void main(String[] args) throws Exception {
        Main main = new Main();
        main.startFramework(args);
    }

    public void startFramework(String[] args) {
        // Read configuration properties.
        Map<String, String> configProps = Main.loadConfigProperties();
        // If no configuration properties were found, then create
        // an empty properties object.
        if (configProps == null) {
            System.err.println("No " + CONFIG_PROPERTIES_FILE_VALUE + " found.");
            configProps = new HashMap<String, String>();
        }
        
     // Set Logging Levels
        String logLevel = System.getProperty("org.ops4j.pax.logging.DefaultServiceLog.level");
        if (logLevel == null) {
            System.setProperty("org.ops4j.pax.logging.DefaultServiceLog.level", "WARN");
        }

        // Load system properties.
        Main.loadSystemProperties();
        

        // Copy framework properties from the system properties.
        Main.copySystemProperties(configProps);
        
        

        // Look for bundle directory and/or cache directory.
        // We support at most one argument, which is the bundle
        // cache directory.

        // Disable Command prompt
        System.setProperty("gosh.args", "--nointeractive");

        // Copy command args
        StringBuilder progArgs = new StringBuilder();

        String pluginDir = null;
        String cacheDir = null;
        boolean expectPluginDir = false;
        boolean batchMode = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--config=")) {
                configProps.put("org.apache.tika.osgi.internal.TikaServiceImpl.tikaConfigPath", args[i].substring(9));
            } else if (args[i].equals("--batch-mode")) {
                batchMode = true;
            }
            progArgs.append(args[i]);
            progArgs.append("\n");
        }

        configProps.put("org.apache.tika.batch.mode", Boolean.toString(batchMode));

        configProps.put("org.apache.tika.command.launchArgs", progArgs.toString());

        int telnetStartPort = Integer.parseInt(configProps.get("osgi.shell.telnet.port"));

        while (!availablePort(telnetStartPort)) {
            System.out.println("Port is not open: " + telnetStartPort);
            telnetStartPort++;
            configProps.put("osgi.shell.telnet.port", Integer.toString(telnetStartPort));
        }

        validateAndPrintUsage(args, pluginDir, expectPluginDir);

        // If there is a passed in plugin directory, then
        // that overwrites anything in the config file.
        if (pluginDir != null) {
            configProps.put(AutoProcessor.PLUGIN_DEPLOY_DIR_PROPERTY, pluginDir);
        }

        // If there is a passed in bundle cache directory, then
        // that overwrites anything in the config file.
        if (cacheDir != null) {
            configProps.put(Constants.FRAMEWORK_STORAGE, cacheDir);
        }

        // If enabled, register a shutdown hook to make sure the framework is
        // cleanly shutdown when the VM exits.
        String enableHook = (String) configProps.get(SHUTDOWN_HOOK_PROP);
        if ((enableHook == null) || !enableHook.equalsIgnoreCase("false")) {
            Runtime.getRuntime().addShutdownHook(new Thread("Felix Shutdown Hook") {
                public void run() {
                    try {
                        if (m_fwk != null) {
                            m_fwk.stop();
                            m_fwk.waitForStop(0);
                        }
                    } catch (Exception ex) {
                        System.err.println("Error stopping framework: " + ex);
                    }
                }
            });
        }

        try {
            // Create an instance of the framework.
            FrameworkFactory factory = getFrameworkFactory();
            m_fwk = factory.newFramework(configProps);
            // Initialize the framework, but don't start it yet.
            m_fwk.init();
            // Use the system bundle context to process the auto-deploy
            // and auto-install/auto-start properties.
            AutoProcessor.process(configProps, m_fwk);
            FrameworkEvent event;
            do {
                // Start the framework.
                m_fwk.start();
                // Wait for framework to stop to exit the VM.
                m_fwk.adapt(FrameworkStartLevel.class).setStartLevel(10, null);
                event = m_fwk.waitForStop(0);
            }
            // If the framework was updated, then restart it.
            while (event.getType() == FrameworkEvent.STOPPED_UPDATE);
            // Otherwise, exit.
            System.exit(0);
        } catch (Exception ex) {
            System.err.println("Could not create framework: " + ex);
            ex.printStackTrace();
            System.exit(0);
        }
    }

    public static void validateAndPrintUsage(String[] args, String pluginDir, boolean expectPluginDir) {
        if ((expectPluginDir && pluginDir == null)) {
            System.out.println("Provide a Plugin Directory for the --plugin option");
            System.exit(0);
        }
    }

    /**
     * Simple method to parse META-INF/services file for framework factory.
     * Currently, it assumes the first non-commented line is the class name of
     * the framework factory implementation.
     * 
     * @return The created <tt>FrameworkFactory</tt> instance.
     * @throws Exception
     *             if any errors occur.
     **/
    private static FrameworkFactory getFrameworkFactory() throws Exception {
        URL url = Main.class.getClassLoader()
                .getResource("META-INF/services/org.osgi.framework.launch.FrameworkFactory");
        if (url != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream(), Charset.forName("UTF-8")));
            try {
                for (String s = br.readLine(); s != null; s = br.readLine()) {
                    s = s.trim();
                    // Try to load first non-empty, non-commented line.
                    if ((s.length() > 0) && (s.charAt(0) != '#')) {
                        return (FrameworkFactory) Class.forName(s).newInstance();
                    }
                }
            } finally {
                if (br != null)
                    br.close();
            }
        }

        throw new Exception("Could not find framework factory.");
    }

    /**
     * <p>
     * Loads the properties in the system property file associated with the
     * framework installation into <tt>System.setProperty()</tt>. These
     * properties are not directly used by the framework in anyway. By default,
     * the system property file is located in the <tt>conf/</tt> directory of
     * the Felix installation directory and is called
     * "<tt>system.properties</tt>". The installation directory of Felix is
     * assumed to be the parent directory of the <tt>felix.jar</tt> file as
     * found on the system class path property. The precise file from which to
     * load system properties can be set by initializing the
     * "<tt>felix.system.properties</tt>" system property to an arbitrary URL.
     * </p>
     **/
    public static void loadSystemProperties() {
        // The system properties file is either specified by a system
        // property or it is in the same directory as the Felix JAR file.
        // Try to load it from one of these places.

        // See if the property URL was specified as a property.
        URL propURL = null;
        String custom = System.getProperty(SYSTEM_PROPERTIES_PROP);
        if (custom != null) {
            try {
                propURL = new URL(custom);
            } catch (MalformedURLException ex) {
                System.err.print("Main: " + ex);
                return;
            }

            // Read the properties file.
            Properties props = new Properties();
            InputStream is = null;
            try {
                is = propURL.openConnection().getInputStream();
                props.load(is);
                is.close();
            } catch (FileNotFoundException ex) {
                // Ignore file not found.
            } catch (Exception ex) {
                System.err.println("Main: Error loading system properties from " + propURL);
                System.err.println("Main: " + ex);
                try {
                    if (is != null)
                        is.close();
                } catch (IOException ex2) {
                    // Nothing we can do.
                }
                return;
            }

            // Perform variable substitution on specified properties.
            for (Enumeration e = props.propertyNames(); e.hasMoreElements();) {
                String name = (String) e.nextElement();
                System.setProperty(name, Util.substVars(props.getProperty(name), name, null, null));
            }
        }

    }

    /**
     * <p>
     * Loads the configuration properties in the configuration property file
     * associated with the framework installation; these properties are
     * accessible to the framework and to bundles and are intended for
     * configuration purposes. By default, the configuration property file is
     * located in the <tt>conf/</tt> directory of the Felix installation
     * directory and is called "<tt>config.properties</tt>". The installation
     * directory of Felix is assumed to be the parent directory of the
     * <tt>felix.jar</tt> file as found on the system class path property. The
     * precise file from which to load configuration properties can be set by
     * initializing the "<tt>felix.config.properties</tt>" system property to an
     * arbitrary URL.
     * </p>
     * 
     * @return A <tt>Properties</tt> instance or <tt>null</tt> if there was an
     *         error.
     **/
    public static Map<String, String> loadConfigProperties() {
        // The config properties file is either specified by a system
        // property or it is in the conf/ directory of the Felix
        // installation directory. Try to load it from one of these
        // places.

        // See if the property URL was specified as a property.
        URL propURL = null;
        String custom = System.getProperty(CONFIG_PROPERTIES_PROP);
        if (custom != null) {
            try {
                propURL = new URL(custom);
            } catch (MalformedURLException ex) {
                System.err.print("Main: " + ex);
                return null;
            }
        } else {
            propURL = Main.class.getClassLoader().getResource(CONFIG_PROPERTIES_FILE_VALUE);
        }

        // Read the properties file.
        Properties props = new Properties();
        InputStream is = null;
        try {
            // Try to load config.properties.
            is = propURL.openConnection().getInputStream();
            props.load(is);
            is.close();
        } catch (Exception ex) {
            // Try to close input stream if we have one.
            try {
                if (is != null)
                    is.close();
            } catch (IOException ex2) {
                // Nothing we can do.
            }

            return null;
        }

        // Perform variable substitution for system properties and
        // convert to dictionary.
        Map<String, String> map = new HashMap<String, String>();
        for (Enumeration e = props.propertyNames(); e.hasMoreElements();) {
            String name = (String) e.nextElement();
            map.put(name, Util.substVars(props.getProperty(name), name, null, props));
        }

        return map;
    }

    public static void copySystemProperties(Map configProps) {
        for (Enumeration e = System.getProperties().propertyNames(); e.hasMoreElements();) {
            String key = (String) e.nextElement();
            if (key.startsWith("felix.") || key.startsWith("org.osgi.framework.")) {
                configProps.put(key, System.getProperty(key));
            }
        }
    }

    /**
     * Find available port for gogo commandline telnet.
     * 
     */
    private static boolean availablePort(int port) {

        ServerSocket ss = null;
        DatagramSocket ds = null;
        try {
            ss = new ServerSocket(port);
            ss.setReuseAddress(true);
            ds = new DatagramSocket(port);
            ds.setReuseAddress(true);
            return true;
        } catch (IOException e) {
        } finally {
            if (ds != null) {
                ds.close();
            }

            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException e) {
                    /* should not be thrown */
                }
            }
        }

        return false;
    }
}
