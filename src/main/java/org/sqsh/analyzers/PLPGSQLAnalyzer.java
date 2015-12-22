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
package org.sqsh.analyzers;

import java.util.Stack;

import org.sqsh.SimpleKeywordTokenizer;

/**
 * Used to analyze Postgres PL-PGSQL
 */
public class PLPGSQLAnalyzer
    implements SQLAnalyzer {
    
    @Override
    public String getName() {

        return "PL-PGSQL";
    }

    /**
     * Analyzes a chunk of PL-PGSQL to determine if the provided terminator
     * character is located at the end of the block. 
     */
    @Override
    public boolean isTerminated (CharSequence sql, char terminator) {
        
        SimpleKeywordTokenizer tokenizer =
            new SimpleKeywordTokenizer(sql, terminator);

        tokenizer.addToTokenCharacters("$");
        
        String prevToken = null;
        String token = tokenizer.next();
        Stack<String>  blockStack = new Stack<String>();
        int inBlock = 0;
        
        while (token != null) {
            
            /*
             * FUNCTION create syntax seesm to use $[token]$ block delimiters.
             */
            if (token.matches("\\$.*\\$")) {
                if (blockStack.isEmpty() || !token.equals(blockStack.peek())) {
                    // Then not in stack, so add it..
                    blockStack.push(token);
                }
                else {
                    blockStack.pop();
                }
            }
            prevToken = token;
            token = tokenizer.next();
        }
        
        return (blockStack.isEmpty()
                    && prevToken != null
                    && prevToken.length() == 1 
                    && prevToken.charAt(0) == terminator);
    }
}
