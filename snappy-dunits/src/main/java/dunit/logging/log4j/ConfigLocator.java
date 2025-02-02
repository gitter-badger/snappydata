package dunit.logging.log4j;

import java.io.File;
import java.net.URL;

import org.apache.logging.log4j.core.util.Loader;
import org.apache.logging.log4j.util.LoaderUtil;

/**
 * Utility methods for finding the Log4J 2 configuration file.
 *
 * TODO This class is copied from GEODE source but put into snappy-dunits.
 * TODO The current package is renamed from com.gemstone.gemfire.internal.logging.log4j to avoid package sealing issue.
 *
 * @author Kirk Lund
 */
public class ConfigLocator {

  static final String PREFIX = "log4j2";
  
  static final String SUFFIX_TEST_YAML = "-test.yaml";
  static final String SUFFIX_TEST_YML = "-test.yml";
  static final String SUFFIX_TEST_JSON = "-test.json";
  static final String SUFFIX_TEST_JSN = "-test.jsn";
  static final String SUFFIX_TEST_XML = "-test.xml";
  static final String SUFFIX_YAML = ".yaml";
  static final String SUFFIX_YML = ".yml";
  static final String SUFFIX_JSON = ".json";
  static final String SUFFIX_JSN = ".jsn";
  static final String SUFFIX_XML = ".xml";
  
  /** Ordered as specified on http://logging.apache.org/log4j/2.x/manual/configuration.html */
  static final String[] SUFFIXES = new String[] { SUFFIX_TEST_YAML, SUFFIX_TEST_YML, SUFFIX_TEST_JSON, SUFFIX_TEST_JSN, SUFFIX_TEST_XML, SUFFIX_YAML, SUFFIX_YML, SUFFIX_JSON, SUFFIX_JSN, SUFFIX_XML };
  
  /**
   * Finds a Log4j configuration file in the current working directory.  The 
   * names of the files to look for are the same as those that Log4j would look 
   * for on the classpath.
   * 
   * @return configuration file or null if not found.
   */
  public static File findConfigInWorkingDirectory() {    
    for (final String suffix : SUFFIXES) {
      final File configFile = new File(System.getProperty("user.dir"), PREFIX + suffix);
      if (configFile.isFile()) {
        return configFile;
      }
    }

    return null;
  }

  /**
   * This should replicate the classpath search for configuration file that 
   * Log4J 2 performs. Returns the configuration location as URI or null if 
   * none is found.
   *
   * @return configuration location or null if not found
   */
  public static URL findConfigInClasspath() {
    final ClassLoader loader = LoaderUtil.getThreadContextClassLoader();
    for (final String suffix : SUFFIXES) {
      String resource = PREFIX + suffix;
      URL url = Loader.getResource(resource, loader);
      if (url != null) {
        // found it
        return url;
      }
    }
    return null;
  }
}
