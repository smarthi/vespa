// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package cmd

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io/ioutil"
	"log"
	"os"
	"path/filepath"
	"strings"

	"github.com/spf13/cobra"
	"github.com/vespa-engine/vespa/client/go/util"
	"github.com/vespa-engine/vespa/client/go/vespa"
	"github.com/vespa-engine/vespa/client/go/vespa/xml"
)

func init() {
	rootCmd.AddCommand(prodCmd)
	prodCmd.AddCommand(prodInitCmd)
	prodCmd.AddCommand(prodSubmitCmd)
}

var prodCmd = &cobra.Command{
	Use:   "prod",
	Short: "Deploy an application package to production in Vespa Cloud",
	Long: `Deploy an application package to production in Vespa Cloud.

Configure and deploy your application package to production in Vespa Cloud.`,
	Example: `$ vespa prod init
$ vespa prod submit`,
	DisableAutoGenTag: true,
	SilenceUsage:      false,
	Args:              cobra.MinimumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		return fmt.Errorf("invalid command: %s", args[0])
	},
}

var prodInitCmd = &cobra.Command{
	Use:   "init",
	Short: "Modify service.xml and deployment.xml for production deployment",
	Long: `Modify service.xml and deployment.xml for production deployment.

Only basic deployment configuration is available through this command. For
advanced configuration see the relevant Vespa Cloud documentation and make
changes to deployment.xml and services.xml directly.

Reference:
https://cloud.vespa.ai/en/reference/services
https://cloud.vespa.ai/en/reference/deployment`,
	DisableAutoGenTag: true,
	SilenceUsage:      true,
	RunE: func(cmd *cobra.Command, args []string) error {
		appSource := applicationSource(args)
		pkg, err := vespa.FindApplicationPackage(appSource, false)
		if err != nil {
			return err
		}
		if pkg.IsZip() {
			return errHint(fmt.Errorf("cannot modify compressed application package %s", pkg.Path),
				"Try running 'mvn clean' and run this command again")
		}

		deploymentXML, err := readDeploymentXML(pkg)
		if err != nil {
			return fmt.Errorf("could not read deployment.xml: %w", err)
		}
		servicesXML, err := readServicesXML(pkg)
		if err != nil {
			return fmt.Errorf("a services.xml declaring your cluster(s) must exist: %w", err)
		}

		fmt.Fprint(stdout, "This will modify any existing ", color.Yellow("deployment.xml"), " and ", color.Yellow("services.xml"),
			"!\nBefore modification a backup of the original file will be created.\n\n")
		fmt.Fprint(stdout, "A default value is suggested (shown inside brackets) based on\nthe files' existing contents. Press enter to use it.\n\n")
		fmt.Fprint(stdout, "Abort the configuration at any time by pressing Ctrl-C. The\nfiles will remain untouched.\n\n")
		fmt.Fprint(stdout, "See this guide for sizing a Vespa deployment:\n", color.Green("https://docs.vespa.ai/en/performance/sizing-search.html\n\n"))
		r := bufio.NewReader(stdin)
		deploymentXML, err = updateRegions(r, deploymentXML)
		if err != nil {
			return err
		}
		servicesXML, err = updateNodes(r, servicesXML)
		if err != nil {
			return err
		}

		fmt.Fprintln(stdout)
		if err := writeWithBackup(pkg, "deployment.xml", deploymentXML.String()); err != nil {
			return err
		}
		if err := writeWithBackup(pkg, "services.xml", servicesXML.String()); err != nil {
			return err
		}
		return nil
	},
}

var prodSubmitCmd = &cobra.Command{
	Use:   "submit",
	Short: "Submit your application for production deployment",
	Long: `Submit your application for production deployment.

This commands uploads your application package to Vespa Cloud and deploys it to
the production zones specified in deployment.xml.

Nodes are allocated to your application according to resources specified in
services.xml.

While submitting an application from a local development environment is
supported, it's strongly recommended that production deployments are performed
by a continuous build system.

For more information about production deployments in Vespa Cloud see:
https://cloud.vespa.ai/en/getting-to-production
https://cloud.vespa.ai/en/automated-deployments`,
	DisableAutoGenTag: true,
	SilenceUsage:      true,
	Example: `$ mvn package # when adding custom Java components
$ vespa prod submit`,
	RunE: func(cmd *cobra.Command, args []string) error {
		target, err := getTarget()
		if err != nil {
			return err
		}
		if target.Type() != "cloud" {
			return fmt.Errorf("%s target cannot deploy to Vespa Cloud", target.Type())
		}
		appSource := applicationSource(args)
		pkg, err := vespa.FindApplicationPackage(appSource, true)
		if err != nil {
			return err
		}
		cfg, err := LoadConfig()
		if err != nil {
			return err
		}
		if !pkg.HasDeployment() {
			return errHint(fmt.Errorf("no deployment.xml found"), "Try creating one with vespa prod init")
		}
		if pkg.TestPath == "" {
			return errHint(fmt.Errorf("no tests found"),
				"The application must be a Java maven project, or include basic HTTP tests under src/test/application/",
				"See https://cloud.vespa.ai/en/getting-to-production")
		}
		// TODO: Always verify tests. Do it before packaging, when running Maven from this CLI.
		if !pkg.IsZip() {
			verifyTests(pkg.TestPath, target)
		}
		isCI := os.Getenv("CI") != ""
		if !isCI {
			fmt.Fprintln(stderr, color.Yellow("Warning:"), "We recommend doing this only from a CD job")
			printErrHint(nil, "See https://cloud.vespa.ai/en/getting-to-production")
		}
		opts, err := getDeploymentOpts(cfg, pkg, target)
		if err != nil {
			return err
		}
		if err := vespa.Submit(opts); err != nil {
			return fmt.Errorf("could not submit application for deployment: %w", err)
		} else {
			printSuccess("Submitted ", color.Cyan(pkg.Path), " for deployment")
			log.Printf("See %s for deployment progress\n", color.Cyan(fmt.Sprintf("%s/tenant/%s/application/%s/prod/deployment",
				getConsoleURL(), opts.Deployment.Application.Tenant, opts.Deployment.Application.Application)))
		}
		return nil
	},
}

func writeWithBackup(pkg vespa.ApplicationPackage, filename, contents string) error {
	dst := filepath.Join(pkg.Path, filename)
	if util.PathExists(dst) {
		data, err := ioutil.ReadFile(dst)
		if err != nil {
			return err
		}
		if bytes.Equal(data, []byte(contents)) {
			fmt.Fprintf(stdout, "Not writing %s: File is unchanged\n", color.Yellow(filename))
			return nil
		}
		renamed := false
		for i := 1; i <= 1000; i++ {
			bak := fmt.Sprintf("%s.%d.bak", dst, i)
			if !util.PathExists(bak) {
				fmt.Fprintf(stdout, "Backing up existing %s to %s\n", color.Yellow(filename), color.Yellow(bak))
				if err := os.Rename(dst, bak); err != nil {
					return err
				}
				renamed = true
				break
			}
		}
		if !renamed {
			return fmt.Errorf("could not find an unused backup name for %s", dst)
		}
	}
	fmt.Fprintf(stdout, "Writing %s\n", color.Green(dst))
	return ioutil.WriteFile(dst, []byte(contents), 0644)
}

func updateRegions(r *bufio.Reader, deploymentXML xml.Deployment) (xml.Deployment, error) {
	regions, err := promptRegions(r, deploymentXML)
	if err != nil {
		return xml.Deployment{}, err
	}
	parts := strings.Split(regions, ",")
	regionElements := xml.Regions(parts...)
	if err := deploymentXML.Replace("prod", "region", regionElements); err != nil {
		return xml.Deployment{}, fmt.Errorf("could not update region elements in deployment.xml: %w", err)
	}
	// TODO: Some sample apps come with production <test> elements, but not necessarily working production tests, we
	//       therefore remove <test> elements here.
	//       This can be improved by supporting <test> elements in xml package and allow specifying testing as part of
	//       region prompt, e.g. region1;test,region2
	if err := deploymentXML.Replace("prod", "test", nil); err != nil {
		return xml.Deployment{}, fmt.Errorf("could not remove test elements in deployment.xml: %w", err)
	}
	return deploymentXML, nil
}

func promptRegions(r *bufio.Reader, deploymentXML xml.Deployment) (string, error) {
	fmt.Fprintln(stdout, color.Cyan("> Deployment regions"))
	fmt.Fprintf(stdout, "Documentation: %s\n", color.Green("https://cloud.vespa.ai/en/reference/zones"))
	fmt.Fprintf(stdout, "Example: %s\n\n", color.Yellow("aws-us-east-1c,aws-us-west-2a"))
	var currentRegions []string
	for _, r := range deploymentXML.Prod.Regions {
		currentRegions = append(currentRegions, r.Name)
	}
	if len(deploymentXML.Instance) > 0 {
		for _, r := range deploymentXML.Instance[0].Prod.Regions {
			currentRegions = append(currentRegions, r.Name)
		}
	}
	validator := func(input string) error {
		regions := strings.Split(input, ",")
		for _, r := range regions {
			if !xml.IsProdRegion(r, getSystem()) {
				return fmt.Errorf("invalid region %s", r)
			}
		}
		return nil
	}
	return prompt(r, "Which regions do you wish to deploy in?", strings.Join(currentRegions, ","), validator)
}

func updateNodes(r *bufio.Reader, servicesXML xml.Services) (xml.Services, error) {
	for _, c := range servicesXML.Container {
		nodes, err := promptNodes(r, c.ID, c.Nodes)
		if err != nil {
			return xml.Services{}, err
		}
		if err := servicesXML.Replace("container#"+c.ID, "nodes", nodes); err != nil {
			return xml.Services{}, err
		}
	}
	for _, c := range servicesXML.Content {
		nodes, err := promptNodes(r, c.ID, c.Nodes)
		if err != nil {
			return xml.Services{}, err
		}
		if err := servicesXML.Replace("content#"+c.ID, "nodes", nodes); err != nil {
			return xml.Services{}, err
		}
	}
	return servicesXML, nil
}

func promptNodes(r *bufio.Reader, clusterID string, defaultValue xml.Nodes) (xml.Nodes, error) {
	count, err := promptNodeCount(r, clusterID, defaultValue.Count)
	if err != nil {
		return xml.Nodes{}, err
	}
	const autoSpec = "auto"
	defaultSpec := autoSpec
	resources := defaultValue.Resources
	if resources != nil {
		defaultSpec = defaultValue.Resources.String()
	}
	spec, err := promptResources(r, clusterID, defaultSpec)
	if err != nil {
		return xml.Nodes{}, err
	}
	if spec == autoSpec {
		resources = nil
	} else {
		r, err := xml.ParseResources(spec)
		if err != nil {
			return xml.Nodes{}, err // Should not happen as resources have already been validated
		}
		resources = &r
	}
	return xml.Nodes{Count: count, Resources: resources}, nil
}

func promptNodeCount(r *bufio.Reader, clusterID string, nodeCount string) (string, error) {
	fmt.Fprintln(stdout, color.Cyan("\n> Node count: "+clusterID+" cluster"))
	fmt.Fprintf(stdout, "Documentation: %s\n", color.Green("https://cloud.vespa.ai/en/reference/services"))
	fmt.Fprintf(stdout, "Example: %s\nExample: %s\n\n", color.Yellow("4"), color.Yellow("[2,8]"))
	validator := func(input string) error {
		_, _, err := xml.ParseNodeCount(input)
		return err
	}
	return prompt(r, fmt.Sprintf("How many nodes should the %s cluster have?", color.Cyan(clusterID)), nodeCount, validator)
}

func promptResources(r *bufio.Reader, clusterID string, resources string) (string, error) {
	fmt.Fprintln(stdout, color.Cyan("\n> Node resources: "+clusterID+" cluster"))
	fmt.Fprintf(stdout, "Documentation: %s\n", color.Green("https://cloud.vespa.ai/en/reference/services"))
	fmt.Fprintf(stdout, "Example: %s\nExample: %s\n\n", color.Yellow("auto"), color.Yellow("vcpu=4,memory=8Gb,disk=100Gb"))
	validator := func(input string) error {
		if input == "auto" {
			return nil
		}
		_, err := xml.ParseResources(input)
		return err
	}
	return prompt(r, fmt.Sprintf("Which resources should each node in the %s cluster have?", color.Cyan(clusterID)), resources, validator)
}

func readDeploymentXML(pkg vespa.ApplicationPackage) (xml.Deployment, error) {
	f, err := os.Open(filepath.Join(pkg.Path, "deployment.xml"))
	if errors.Is(err, os.ErrNotExist) {
		// Return a default value if there is no current deployment.xml
		return xml.DefaultDeployment, nil
	} else if err != nil {
		return xml.Deployment{}, err
	}
	defer f.Close()
	return xml.ReadDeployment(f)
}

func readServicesXML(pkg vespa.ApplicationPackage) (xml.Services, error) {
	f, err := os.Open(filepath.Join(pkg.Path, "services.xml"))
	if err != nil {
		return xml.Services{}, err
	}
	defer f.Close()
	return xml.ReadServices(f)
}

func prompt(r *bufio.Reader, question, defaultAnswer string, validator func(input string) error) (string, error) {
	var input string
	for input == "" {
		fmt.Fprint(stdout, question)
		if defaultAnswer != "" {
			fmt.Fprint(stdout, " [", color.Yellow(defaultAnswer), "]")
		}
		fmt.Fprint(stdout, " ")

		var err error
		input, err = r.ReadString('\n')
		if err != nil {
			return "", err
		}
		input = strings.TrimSpace(input)
		if input == "" {
			input = defaultAnswer
		}

		if err := validator(input); err != nil {
			printErr(err)
			fmt.Fprintln(stderr)
			input = ""
		}
	}
	return input, nil
}

func verifyTests(testsParent string, target vespa.Target) {
	verifyTest(testsParent, "system-test", target, true)
	verifyTest(testsParent, "staging-setup", target, true)
	verifyTest(testsParent, "staging-test", target, true)
	verifyTest(testsParent, "production-test", target, false)
}

func verifyTest(testsParent string, suite string, target vespa.Target, required bool) error {
	testDirectory := filepath.Join(testsParent, "tests", suite)
	_, err := os.Stat(testDirectory)
	if err != nil {
		if required {
			if errors.Is(err, os.ErrNotExist) {
				return errHint(fmt.Errorf("no %s tests found: %w", suite, err),
					fmt.Sprintf("No such directory: %s", testDirectory),
					"See https://cloud.vespa.ai/en/reference/testing")
			}
			return errHint(err, "See https://cloud.vespa.ai/en/reference/testing")
		}
		return nil
	}
	_, _, err = runTests(testDirectory, true)
	return err
}
