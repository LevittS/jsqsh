/*
 * Copyright 2007-2012 Scott C. Gray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sqsh;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.LogManager;

import org.sqsh.commands.Jaql;
import org.sqsh.options.Argv;
import org.sqsh.options.OptionProperty;
import org.sqsh.options.OptionException;
import org.sqsh.options.OptionProcessor;

import static org.sqsh.options.ArgumentRequired.REQUIRED;
import static org.sqsh.options.ArgumentRequired.NONE;

/**
 * This implements the public command line interface to kicking off
 * sqsh.
 */
public class JSqsh {

    private static final String LOGGING_CONFIG
        = "org/sqsh/logging.properties";
    
    private static class Options
        extends ConnectionDescriptor {
        
       @OptionProperty(
           option='i', longOption="input-file", arg=REQUIRED, argName="file",
           description="Name of file to read as input instead of stdin")
       public String inputFile = null;
       
       @OptionProperty(
           option='o', longOption="output-file", arg=REQUIRED, argName="file",
           description="Name of file send output instead of stdout")
       public String outputFile = null;
       
       @OptionProperty(
           option='e', longOption="echo", arg=NONE, 
           description="Echoes all input back. Useful for running scripts.")
       public boolean isInputEchoed = false;
       
       @OptionProperty(
           option='n', longOption="non-interactive", arg=NONE,
           description="Force the session to be non-interactive "
                           + "(not yet implemented)")
       public boolean nonInteractive = false;
       
       @OptionProperty(
           option='j', longOption="jaql", arg=NONE,
           description="Start the session in Jaql mode")
       public boolean jaqlMode = false;
       
       @OptionProperty(
           option='p', longOption="jaql-path", arg=REQUIRED,
           description="Colon separated list of jaql module search directories")
       public String jaqlSearchPath = null;
       
       @OptionProperty(
           option='J', longOption="jaql-jars", arg=REQUIRED,
           description="Comma separated list of jars to be used by the jaql shell")
       public String jaqlJars = null;
       
       @OptionProperty(
           option='b', longOption="debug", arg=REQUIRED, argName="class",
           description="Turn on debugging for a java class or package")
       public List<String> debug = new ArrayList<String>();
       
       @OptionProperty(
           option='r', longOption="readline", arg=REQUIRED, argName="method",
           description="Readline method "
                     + "(readline,editline,getline,jline,purejava)")
       public String readline = null;

       @OptionProperty(
           option='C', longOption="config-dir", arg=REQUIRED, argName="dir",
           description="Configuration directory in addition to $HOME/.jsqsh.")
       public List<String> configDirectories = new ArrayList<String>();

       @OptionProperty(
           option='R', longOption="drivers", arg=REQUIRED, argName="file",
           description="Specifies additional drivers.xml files to be loaded")
       public List<String> driverFiles = new ArrayList<String>();
       
       @Argv(program="jsqsh", min=0, max=1, usage="[options] [connection-name]")
       public List<String> arguments = new ArrayList<String>();
    }
   
    public static void main (String argv[]) {
        
        Options options = new Options();
        OptionProcessor optParser = new OptionProcessor(options);
        
        try {
            
            optParser.parseOptions(argv);
        }
        catch (OptionException e) {
            
            System.err.println(e.getMessage());
            System.err.println(optParser.getUsage());
            
            System.exit(1);
        }
        
        /*
         * Display help if requested.
         */
        if (options.doHelp) {
            
            System.out.println(optParser.getUsage());
            System.exit(0);
        }
        
        configureLogging(options.debug);
        
        
        InputStream in = getInputStream(options);
        PrintStream out = getOutputStream(options);
        PrintStream err = System.err;
        
        if (in == null || out == null || err == null) {
            
            System.exit(1);
        }
        
        SqshContext sqsh = new SqshContext(options.readline);
        int rc = 0;

        for (String dir : options.configDirectories) {

            sqsh.addConfigurationDirectory(dir);
        }

        for (String file : options.driverFiles) {

            sqsh.addDriverFile(file);
        }
        
        sqsh.setInputEchoed(options.isInputEchoed);
        
        try {
            
            Session session = sqsh.newSession();
            session.setIn(in, (options.inputFile != null), 
                (options.inputFile == null));
            session.setOut(out, options.outputFile != null);
            
            if (options.nonInteractive)
                session.setInteractive(false);
            
            if (options.jaqlMode 
                    || options.jaqlJars != null
                    || options.jaqlSearchPath != null) {
                
                if (!doJaql(session, options)) {
                    
                    rc = 1;
                }
            }
            else if (!doConnect(session, options)) {
                
                rc = 1;
            }
            
            if (rc == 0) {
            
                rc = sqsh.run();
            }
        }
        catch (Throwable e) {
            
            e.printStackTrace(System.err);
            rc = 1;
        }
        finally {
            
            out.flush();
            err.flush();
            
            if (out != System.out && out != System.err) {
                
                out.close();
            }
            if (err != System.err && err != System.out) {
                
                err.close();
            }
            
            if (in != System.in) {
                
                try {
                    
                    in.close();
                }
                catch (IOException e) {
                    
                    /* IGNORED */
                }
            }

            sqsh.close();
        }
        
        System.exit(rc);
    }

    static private void configureLogging(List<String> loggers) {
        
        InputStream in = 
            JSqsh.class.getClassLoader().getResourceAsStream(LOGGING_CONFIG);
        if (in == null) {
            
            System.err.println("WARNING: Cannot find resource " 
                + LOGGING_CONFIG);
            return;
        }
        
        try {
            
            LogManager logMan = LogManager.getLogManager();
            logMan.readConfiguration(in);
            in.close();
        }
        catch (IOException e) {
            
            System.err.println("WARNING: Unable to read logging "
                + "properties " + LOGGING_CONFIG + ": " + e.getMessage());
        }

        /*
         * Turn on debugging if requested.
         */
        for (String logger : loggers) {
            
            Logger log = Logger.getLogger(logger);
            if (log != null) {
                
                log.setLevel(Level.FINE);
                System.out.println("Debugging level for '"
                    + log.getName() + "' is now '"
                    + log.getLevel().getName() + "'");
            }
            else {
                
                System.err.println("--debug: Unable to find logger '"
                    + logger + "'");
                System.exit(1);
            }
        }
    }
    
    /**
     * Returns the input stream to be used by the session.
     * 
     * @param options Configuration options.
     * @return The input stream or null if the requested one cannot be
     *    opened.
     */
    private static InputStream getInputStream(Options options) {
        
        if (options.inputFile != null) {
            
            try {
                
                InputStream in = new BufferedInputStream(
                    new FileInputStream(options.inputFile));
                
                return in;
            }
            catch (IOException e) {
                
                System.err.println("Unable to open input file '" 
                    + options.inputFile + "' for read: "
                    + e.getMessage());
                
                return null;
            }
        }
        
        return System.in;
    }
    
    /**
     * Returns the output stream to be used by the session.
     * 
     * @param options Configuration options.
     * @return The input stream or null if the requested one cannot be
     *    opened.
     */
    private static PrintStream getOutputStream(Options options) {
        
        if (options.outputFile != null) {
            
            try {
                
                PrintStream out = new PrintStream(options.outputFile);
                return out;
            }
            catch (IOException e) {
                
                System.err.println("Unable to open output file '" 
                    + options.outputFile + "' for write: "
                    + e.getMessage());
                
                return null;
            }
        }
        
        return System.out;
    }
    
    /**
     * If --jaql was provided, sets up the session to be a jaql session.
     * 
     * @param session The session
     * @param options The options provided to start jsqsh
     * @return true if teh jaql session was successfully started, false
     *   otherwise.
     */
    private static boolean doJaql(Session session, Options options) {
        
        Jaql.Options jaqlOptions = new Jaql.Options();
        Jaql cmd = new Jaql();
        
        if (options.jaqlJars != null)
            jaqlOptions.jars = options.jaqlJars;
        if (options.jaqlSearchPath != null)
            jaqlOptions.jaqlPath = options.jaqlSearchPath;
        
        try {
            
            if (cmd.execute(session, jaqlOptions) != 0) {
                
                return false;
            }
        }
        catch (Exception e) {
            
            e.printStackTrace();
            return false;
        }
        
        return true;
    }
    
    /**
     * 
     * Ok, I'm lazy. Since most of the command line options are there
     * to allow the caller to pre-connect sqsh, I am actually just
     * going to build a connect command and execute it rather than
     * messing with API calls to do the dirty work for me.
     * 
     * @param options The command line options that were passed it.
     * @return A string containing a connect command, or null if no
     *   connection is necessary.
     */
    private static boolean doConnect(Session session, Options options) {
        
        ConnectionDescriptor connDesc = (ConnectionDescriptor)options;
        
        /*
         * If any one of our options having to do with establish a connection
         * have been provided, then connect!
         */
        if (options.getServer() != null
                || options.getPort() != -1
                || options.getCatalog() != null
                || options.getUsername() != null
                || options.getPassword() != null
                || options.getSid() != null
                || options.getJdbcClass() != null
                || options.getDriver() != null
                || options.getDomain() != null
                || options.arguments.size() > 0) {
            
            if (options.arguments.size() > 0) {
                
                String name = options.arguments.get(0);
                ConnectionDescriptorManager connDescMan = 
                    session.getConnectionDescriptorManager();
                
                ConnectionDescriptor savedOptions = connDescMan.get(name);
                if (savedOptions == null) {
                    
                    session.err.println("There is no saved connection "
                        + "information named '" + name + "'.");
                    
                    return false;
                }
                
                connDesc = connDescMan.merge(savedOptions, connDesc);
            }
            
            try {
                
                ConnectionContext ctx = 
                    session.getDriverManager().connect(session, connDesc);
                session.setConnectionContext(ctx);
            }
            catch (SQLException e) {
                
                SQLTools.printException(session, e);
                return false;
            }
        }
        
        return true;
    }
}
