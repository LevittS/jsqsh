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
package org.sqsh.commands;

import static org.sqsh.options.ArgumentRequired.REQUIRED;
import static org.sqsh.options.ArgumentRequired.NONE;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.sqsh.SqshTypes;
import org.sqsh.BufferManager;
import org.sqsh.CallParameter;
import org.sqsh.CannotSetValueError;
import org.sqsh.Command;
import org.sqsh.ConnectionContext;
import org.sqsh.DatabaseCommand;
import org.sqsh.SQLRenderer;
import org.sqsh.SQLTools;
import org.sqsh.Session;
import org.sqsh.SqshOptions;
import org.sqsh.Style;
import org.sqsh.options.Argv;
import org.sqsh.options.OptionProperty;
import org.sqsh.util.CSVReader;

/**
 * Implements the \call command.
 */
public class Call
    extends Command
    implements DatabaseCommand {
    
    private static class Options
    extends SqshOptions {
        
        @OptionProperty(
            option='f', longOption="file", arg=REQUIRED, argName="file",
            description="CSV file to be used for parameters to query")
        public String inputFile = null;
        
        @OptionProperty(
            option='m', longOption="display-style", arg=REQUIRED,
            description="Sets the display style for output")
        public String style = null;

        @OptionProperty(
            option='i', longOption="ignore-header", arg=NONE,
            description="Ignore headers in input file")
        public boolean hasHeaders = false;

        @Argv(program="\\call", min=0)
        public List<String> arguments = new ArrayList<String>();
    }
    
    /**
     * Return our overridden options.
     */
    @Override
    public SqshOptions getOptions() {
        
        return new Options();
    }

    @Override
    public int execute (Session session, SqshOptions opts)
        throws Exception {
        
        Options options = (Options)opts;
        Style origStyle = null;
        String argv[] = options.arguments.toArray(new String[0]);
        
        BufferManager bufferMan = session.getBufferManager();
        String sql = bufferMan.getCurrent().toString();
        ConnectionContext conn = session.getConnectionContext();
        
        /*
         * If this is an interactive session, then we create a new
         * buffer to work with, otherwise we just re-use the current
         * buffer.
         */
        if (session.isInteractive()) {
            
            bufferMan.newBuffer();
        }
        else {
            
            bufferMan.getCurrent().clear();
        }
        
        /*
         * Parse the command line arguments so we know how they are
         * going to be handled.
         */
        Parameter []params = new Parameter[argv.length];
        for (int i = 0; i < argv.length; i++) {
            
            try {
                
                params[i] = new Parameter(argv[i], i+1);
            }
            catch (Exception e) {
                
                session.err.println("Unable to parse '"
                    + argv[i] + "': " + e.getMessage());
                return 1;
            }
        }
        
        try {
            if (options.style != null) {
                origStyle = conn.getStyle();
                try {
                    conn.setStyle(options.style);
                }
                catch (CannotSetValueError e) {
                    session.err.println(e.getMessage());
                    return 1;
                }
            }
            
            if (options.inputFile == null) {
                
                doNoInputFile(session, sql, params);
            }
            else {
                
                doInputFile(session, sql, options.inputFile,
                    options.hasHeaders, params);
            }
        }
        catch (SQLException e) {
            
            SQLTools.printException(session, e);
            return 1;
        }
        finally {
            if (origStyle != null) {
                conn.setStyle(origStyle);
            }
        }
        
        return 0;
    }
    
    /**
     * This uses a crummy heuristic to figure out of a block that
     * is going to be executed is a stored procedure call or is
     * a prepared statement and attempts to prepare it accordingly.
     * If the statement starts with a "{" it is assumed to be a
     * stored procedure call, otherwise it is a prepared statement.
     * 
     * @param sql The sql to be evaluated.
     * @return true if the sql contains a call.
     */
    private boolean isCall (String sql) {
        
        int idx = 0;
        while (idx < sql.length() && Character.isWhitespace(sql.charAt(idx))) {
            
            ++idx;
        }
        
        if (idx < sql.length() && sql.charAt(idx) == '{') {
            
            return true;
        }
        
        return false;
    }
    
    
    /**
     * Called to execute the SQL buffer when there is no input file
     * supplied by the caller.
     * 
     * @param session The session context.
     * @param sql The block of SQL to execute.
     * @param params Parameters to the block.
     * @return 0 if it works ok, 1 otherwise.
     * @throws SQLException Thrown if there is an exception.
     */
    private int doNoInputFile(Session session, String sql,
            Parameter []params)
        throws SQLException {
        
        SQLRenderer sqlRenderer = session.getSQLRenderer();
            
        if (isCall(sql)) {
                
            sqlRenderer.executeCall(session, sql, params);
        }
        else {
                
            sqlRenderer.executePrepare(session, sql, params);
        }
        
        return 0;
    }
    
    private int doInputFile(Session session, String sql, String file,
            boolean hasHeaders, Parameter []params)
        throws SQLException {
        
        SQLRenderer sqlRenderer = session.getSQLRenderer();
        boolean isCall = isCall(sql);
        
        InputStream in = null;
        int line = 0;
        int rc = 0;
        
        try {
            
            in = new FileInputStream(file);
            CSVReader reader = new CSVReader(in, hasHeaders);
            
            String row[] = reader.next();
            
            /*
             * If the user passed no parameter description to us, then
             * we'll create them ourselves based upon the file provided.
             */
            if (params.length == 0) {
                
                params = new Parameter[row.length];
                for (int i = 0; i < row.length; i++) {
                    
                    params[i] = new Parameter("S:#" + (i+1), i+1);
                }
            }
            
            while (row != null) {
                
                ++line;
                if (!setParameters(session, line, row, params)) {
                    
                    return 1;
                }
                
                if (isCall) {
                        
                    sqlRenderer.executeCall(session, sql, params);
                }
                else {
                        
                    sqlRenderer.executePrepare(session, sql, params);
                }
                
                row = reader.next();
            }
        }
        catch (IOException e) {
            
            session.err.println("I/O error while reading '"
                + file + "': " + e.getMessage());
            rc = 1;
        }
        
        if (in != null) {
            
            try {
                
                in.close();
            }
            catch (IOException e) {
                
                /* IGNORED */
            }
        }
        
        return rc;
    }
    
    private boolean setParameters(Session session, int line, String []row,
            Parameter []params)
        throws SQLException {
        
        for (Parameter param : params) {
            
            try {
                
                if (param.getColumnIdx() >= 0) {
                    
                    param.setValue(row[param.getColumnIdx()]);
                }
            }
            catch (ArrayIndexOutOfBoundsException e) {
                
                session.err.println("Line #" + line 
                    + " does not contain requested column #"
                    + param.getColumnIdx());
                return false;
            }
        }
        
        return true;
    }
    
    public static class Parameter
        extends CallParameter {
        
        private int columnIdx = -1;
        private String description;
        private boolean valueSet = false;
        private int precision = -1;
        private int scale = -1;
        
        public Parameter (String description, int idx) {
            
            super(idx);
            
            char type = 'S';
            boolean hasInput = true;
            
            /*
             * By default, the value is the description.
             */
            this.description = description;
            
            if ((description.length() >= 2) && ":!^".indexOf(description.charAt(1)) != -1) {
                /**
                 *
                 * [Type][Value]
                 *
                 * Type:
                 * Input [lower]
                 * Output [upper] (No Value)
                 * InOut (upper] (With value / Null Value)
                 *
                 * Value:
                 * With Value '=[Val]'
                 * Null Value '!'
                 * No Value '^'
                 *
                 * Examples:
                 * s=Hello - String In (Hello).
                 * S=Hello - String InOut (Hello);
                 * s! - String In (NULL)
                 * S! - String InOut (NULL)
                 * S^ - String Out
                 * s^  - String (Unset).
                 */
                type = description.charAt(0);
                switch (description.charAt(1)) {
                    case ':':
                        setValue(idx, description.substring(2));
                        break;
                    case '!':
                        setValue(null);
                        break;
                    case '^':
                        hasInput = false;
                        break;
                }
            }
            else {
                setValue(idx, description);
            }

            if (Character.toUpperCase(type) != type) {
                setDirection(INPUT);
            }
            else {
                setDirection(hasInput ? CallParameter.INOUT : CallParameter.OUTPUT);
            }
            switch (Character.toUpperCase(type)) {
                case 'S':
                case 'C':
                    setType(Types.VARCHAR);
                    break;
                case 'Z':
                    setType(Types.BOOLEAN);
                    break;
                case 'D':
                    setType(Types.DOUBLE);
                    break;
                case 'F':
                    setType(Types.FLOAT);
                    break;
                case 'I':
                    setType(Types.INTEGER);
                    break;
                case 'J':
                    setType(Types.BIGINT);
                    break;
                case 'R':
                    setType(SqshTypes.SQSH_CURSOR);
                    break;
                case 'U':
                    setType(SqshTypes.SQSH_UNDETERMINED);
                    break;
            }
        }


        private void setValue(int idx, String value)
        {
            if (value.length() > 0 && value.charAt(0) == '#') {
                if (value.length() == 1) {
                    columnIdx = idx - 1;
                }
                else {
                    columnIdx = Integer.parseInt(value.substring(1)) - 1;
                }
            }
            else {
                setValue(value);
            }
        }


        public int getColumnIdx() {
            
            return columnIdx;
        }

        
        public String getDescription() {
            
            return description;
        }


        public void setMetaDataDetails(int type, int precision, int scale)
        {
            setType(type);
            this.precision = precision;
            this.scale = scale;
        }


        public int getPrecision()
        {
            return precision;
        }


        public boolean hasPrecision()
        {
            return precision != -1;
        }


        public int getScale()
        {
            return scale;
        }
        
    }
}
