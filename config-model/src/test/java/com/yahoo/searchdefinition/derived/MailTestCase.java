// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.derived;

import com.yahoo.searchdefinition.ApplicationBuilder;
import com.yahoo.searchdefinition.parser.ParseException;
import org.junit.Test;
import java.io.IOException;

/**
 * Tests streaming configuration deriving
 *
 * @author bratseth
 */
public class MailTestCase extends AbstractExportingTestCase {

    @Test
    public void testMail() throws IOException, ParseException {
        String dir = "src/test/derived/mail/";
        ApplicationBuilder sb = new ApplicationBuilder();
        sb.addSchemaFile(dir + "mail.sd");
        assertCorrectDeriving(sb, dir, new TestableDeployLogger());
    }

}
