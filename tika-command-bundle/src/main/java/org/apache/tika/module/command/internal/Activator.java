/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tika.module.command.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Properties;
import java.util.Map.Entry;

import org.apache.commons.lang.StringUtils;
import org.apache.tika.batch.BatchProcessDriverCLI;
import org.apache.tika.cli.CommandStatus;
import org.apache.tika.cli.TikaCLI;
import org.apache.tika.cli.batch.BundleBatchCLI;
import org.apache.tika.osgi.TikaServiceFactory;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MarkerFactory;

public class Activator implements BundleActivator {
    
    private static Logger LOG = LoggerFactory.getLogger(Activator.class);

    @Override
    public void start(BundleContext context) throws Exception {
        
        configureLogging(context);
        
        boolean batchMode = Boolean.parseBoolean(context.getProperty("org.apache.tika.batch.mode"));

        String commandString = context.getProperty("org.apache.tika.command.launchArgs");
        
        String[] command = new String[0];
        if(StringUtils.isNotBlank(commandString))
        {
            command = commandString.split("\\n");
        }
        
        boolean fork = Arrays.binarySearch(command, "-f") >= 0 || Arrays.binarySearch(command, "--fork") >= 0;
        ServiceReference<TikaServiceFactory> tikaServiceFactoryRef = 
                context.getServiceReference(TikaServiceFactory.class); 

        if (fork) {
            forkProcess(command);
            stopFramework(context);
        }
        else if(batchMode)
        {
            runBatch(context, command);
            stopFramework(context);
        }
        else
        {
            runCLI(context, command, tikaServiceFactoryRef);
            
        }
    }

    public void runCLI(BundleContext context, String[] command,
            ServiceReference<TikaServiceFactory> tikaServiceFactoryRef)
                    throws Exception, BundleException, InterruptedException {
        try {
            TikaCLI commandRunner = new TikaCLI(context.getService(tikaServiceFactoryRef));
            CommandStatus result = commandRunner.run(command);
            if(result.equals(CommandStatus.COMPLETE))
            {
                stopFramework(context);
            }
        } catch (Exception e) {
            stopFramework(context);
        }
    }

    public void stopFramework(BundleContext context) throws BundleException, InterruptedException {
        Framework systemBundle = context.getBundle(0).adapt(Framework.class);
        systemBundle.stop();
        systemBundle.waitForStop(2000);
    }

    public void runBatch(BundleContext context, String[] command) {
        String[] batchCommand = new String[command.length -1];
        int currentCommandPosition = 0;
        for(String currentCommand: command)
        {
            if(!currentCommand.equals("--batch-mode"))
            {
                batchCommand[currentCommandPosition] = currentCommand;
                currentCommandPosition++;
            }
        }
        try{
            BundleBatchCLI cli = new BundleBatchCLI(batchCommand, context);
            cli.execute(batchCommand);
        } catch (Throwable t) {
            t.printStackTrace();
            LOG.error(MarkerFactory.getMarker("FATAL"),
                    "Fatal exception from BundleBatchCLI: " + t.getMessage(), t);
            System.exit(BatchProcessDriverCLI.PROCESS_NO_RESTART_EXIT_CODE);
        }
    }

    public void forkProcess(String[] command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder();
        builder.redirectOutput(Redirect.INHERIT);
        builder.redirectError(Redirect.INHERIT);
        List<String> forkCommand = new ArrayList<String>();
        forkCommand.add("java");
        forkCommand.add("-cp");
        forkCommand.add(System.getProperty("java.class.path"));
        forkCommand.add("org.apache.tika.main.Main");
        forkCommand.addAll(Arrays.asList(command));
        
        //Remove fork command when running process forked.
        forkCommand.remove("-f");
        forkCommand.remove("--fork");
        
        builder.command(forkCommand);
        Process process = builder.start();
        
        process.waitFor();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        // TODO Auto-generated method stub

    }
    
    private void configureLogging(BundleContext context) {
        ConfigurationAdmin configAdmin = getConfigurationAdmin(context);
        
        try {
            final Configuration configuration = configAdmin.getConfiguration( "org.ops4j.pax.logging", null );
            String log4JConfigUri = System.getProperty("log4j.configuration");
            if(log4JConfigUri != null)
            {
                Properties log4JProps = new Properties();
                URL log4jUrl = Activator.class.getClassLoader().getResource(log4JConfigUri);
                InputStream log4jInputStream = null;
                if(log4jUrl == null)
                {
                    log4jInputStream = new FileInputStream(new File(new URI(log4JConfigUri)));
                }
                else
                {
                    log4jInputStream = log4jUrl.openStream();
                }
                log4JProps.load(log4jInputStream);
  
                Dictionary<String, String> logProps = new Hashtable<>();
                for(Entry currentProperty : log4JProps.entrySet())
                {
                    String key = (String)currentProperty.getKey();
                    String value = (String)currentProperty.getValue();
                    logProps.put(key, value);
                }
                configuration.update(logProps);
            }
        } catch (Exception e) {
            LOG.warn("Logging could not be properly configured", e);
        }
    }
    
    
    private ConfigurationAdmin getConfigurationAdmin( final BundleContext bundleContext )
    {
        final ServiceReference ref = bundleContext.getServiceReference( ConfigurationAdmin.class.getName() );
        if( ref == null )
        {
            throw new IllegalStateException( "Cannot find a configuration admin service" );
        }
        return (ConfigurationAdmin) bundleContext.getService( ref );
    }

}
