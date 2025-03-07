// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/vespalib/testkit/testapp.h>
#include <vespa/vespalib/util/child_process.h>
#include <sys/wait.h>

using namespace vespalib;

class Test : public TestApp
{
public:
    int Main() override;
private:
    virtual bool useProcessStarter() const override { return true; }
};

int Test::Main()
{
    TEST_INIT("expectsignal_test");

    EXPECT_EQUAL(_argc, 3);
    ASSERT_TRUE(_argc == 3);

    int retval = strtol(_argv[1], NULL, 0);

    fprintf(stderr, "argc=%d : Running '%s' expecting signal %d\n", _argc, _argv[2], retval);

    ChildProcess cmd(_argv[2]);
    for(std::string line; cmd.readLine(line, 60000);) {
        fprintf(stdout, "%s\n", line.c_str());
    }

    ASSERT_TRUE(cmd.wait(60000));

    int exitCode = cmd.getExitCode();

    if (exitCode == 65535) {
        fprintf(stderr, "[ERROR] child killed (timeout)\n");
    } else if (WIFEXITED(exitCode)) {
        fprintf(stderr, "child terminated normally with exit code %u\n", WEXITSTATUS(exitCode));
    } else if (WIFSIGNALED(exitCode)) {
        fprintf(stderr, "child terminated by signal %u\n", WTERMSIG(exitCode));
        if (WCOREDUMP(exitCode)) {
            fprintf(stderr, "[WARNING] child dumped core\n");
        }
    } else {
        fprintf(stderr, "[WARNING] strange exit code: %u\n", exitCode);
    }

    EXPECT_EQUAL(exitCode & 0x7f, retval);

    TEST_DONE();
}

TEST_APPHOOK(Test)
