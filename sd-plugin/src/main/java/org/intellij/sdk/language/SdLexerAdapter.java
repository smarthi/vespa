// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package org.intellij.sdk.language;

import com.intellij.lexer.FlexAdapter;
import org.intellij.sdk.language.lexer.SdLexer;

public class SdLexerAdapter extends FlexAdapter {
    
    public SdLexerAdapter() {
        super(new SdLexer(null));
    }
}
