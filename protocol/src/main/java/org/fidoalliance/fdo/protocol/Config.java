// Copyright 2022 Intel Corporation
// SPDX-License-Identifier: Apache 2.0

package org.fidoalliance.fdo.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import org.fidoalliance.fdo.protocol.db.HibernateUtil;
import org.hibernate.Session;

public class Config {

  private static final String ENV_PARAM_START = "$(";
  private static final String ENV_PARAM_END = ")";
  private static final String CONFIG_HOME = "fdo.config.home";

  private static final Root ROOT;
  private static final Properties env = new Properties();
  private static List<Object> workers = new ArrayList<>();
  private static final List<Object> configs = new ArrayList<>();

  private static String configPath;
  private static final String CONFIG_FILE = "service.yml";

  static {

    if (System.getProperty(CONFIG_HOME) != null) {
      configPath = System.getProperty(CONFIG_HOME);
    } else {
      configPath = System.getProperty("user.dir");
      env.setProperty(CONFIG_HOME, configPath);
    }

    File file = Path.of(getPath(), CONFIG_FILE).toFile();

    if (!file.exists()) {
      throw new RuntimeException(new FileNotFoundException(file.toString()));
    }

    try {
      ROOT = Mapper.INSTANCE.readStringValue(file, Root.class);
      loadSystemProperties();
      loadWorkerItems();
      loadEnvFiles();
    } catch (
        IOException e) {
      throw new RuntimeException(e);
    }
  }


  public static class KeyStoreConfig {

    @JsonProperty("path")
    private String path;

    @JsonProperty("password")
    private String password;

    @JsonProperty("store-type")
    private String storeType;

    @JsonProperty("alias")
    private String alias;

    @JsonProperty("provider")
    private String provider;

    public String getPassword() {
      return resolve(password);
    }

    public String getPath() {
      return resolvePath(path);
    }

    public String getStoreType() {
      return resolve(storeType);
    }

    public String getAlias() {
      return resolve(alias);
    }


    public void store(KeyStore keyStore) throws IOException {

    }

  }


  public static class Root {

    @JsonProperty("workers")
    private String[] workers = new String[0];

    @JsonProperty("system-properties")
    private Map<String, String> systemProperties = new HashMap<>();


  }

  private static void loadEnvFiles() {
    FilenameFilter filter = (file, fileName) -> fileName.toLowerCase().endsWith(".env");

    File folder = Path.of(getPath()).toFile();
    File[] files = folder.listFiles(filter);

    if (files != null) {
      for (File file : files) {
        loadConfig(file);
      }
    }

  }

  private static void loadConfig(File file) {
    Properties props = new Properties();

    try (FileInputStream fin = new FileInputStream(file);) {
      props.load(fin);
    } catch (FileNotFoundException e) {
      throw new RuntimeException(file.getName(), e);
    } catch (IOException e) {
      throw new RuntimeException(file.getName(), e);
    }

    Enumeration<?> propEnum = props.keys();
    while (propEnum.hasMoreElements()) {
      String key = propEnum.nextElement().toString();
      String value = props.getProperty(key);
      env.setProperty(key, value);
    }
  }

  /**
   * Resolves any environment environment variables to a path
   *
   * @param path The path string to resolve.
   * @return The resolved path
   */
  public static String resolvePath(String path) {
    if (null == path) {
      return getPath();
    }
    Path checkPath = Path.of(path);
    if (checkPath.isAbsolute()) {
      return checkPath.toString();
    }

    return Path.of(getPath(), path).toString();
  }

  public static Map<String, String> resolve(final Map<String, String> map) {

    Map<String, String> result = new HashMap<>();
    for (Map.Entry<String, String> entry : map.entrySet()) {
      result.put(entry.getKey(), resolve(entry.getValue()));
    }
    return map;
  }


  public static String[] resolve(final String[] value) {
    String[] result = new String[value.length];
    for (int i = 0; i < value.length; i++) {
      result[i] = resolve(value[i]);
    }
    return result;
  }

  /**
   * Resolve environment variables in value.
   *
   * @param value The value to resolve.
   * @return The resolves value.
   */
  public static String resolve(final String value) {
    String result = value;
    if (result != null) {
      result = getEnvValue(value);
    }
    return result;
  }



  private static String getEnvValue(String value) {

    String result = value;
    for (; ; ) {

      int start = result.indexOf(ENV_PARAM_START);
      if (start >= 0) {
        int end = result.indexOf(ENV_PARAM_END, start);

        if (end > start) {
          String envName = result.substring(start + ENV_PARAM_START.length(), end);
          String envValue = System.getenv(envName);
          if (null == envValue) {
            envValue = env.getProperty(envName);
            if (null == envValue) {
              envValue = env.getProperty(envName);
              if (envValue == null) {
                throw new RuntimeException(new NoSuchElementException(envName));
              }
            }
          }
          result = result.substring(0, start) + envValue + value.substring(end + 1);
        } else {
          break;
        }
      } else {
        break;
      }
    }
    return result;
  }


  private static void loadWorkerItems() {
    String[] workerNames = ROOT.workers;
    if (workerNames != null) {
      for (String name : workerNames) {
        Object worker = loadObject(name);
        workers.add(worker);
      }

    }
    ROOT.workers = new String[0];
    workers = Collections.unmodifiableList(workers);
  }

  /**
   * Loads an object by its class name.
   *
   * @param name The class name of the object to load.
   * @return A new instance of the class.
   */
  public static Object loadObject(String name) {
    try {
      Constructor[] ctors = Class.forName(name).getDeclaredConstructors();
      Constructor ctor = null;
      for (int i = 0; i < ctors.length; i++) {
        ctor = ctors[i];
        if (ctor.getGenericParameterTypes().length == 0) {
          break;
        }
      }

      if (ctor == null) {
        throw new RuntimeException(
            new InstantiationException("class requires constructor with no args."));
      }
      return ctor.newInstance(new Object[]{});

    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    } catch (InvocationTargetException e) {
      throw new RuntimeException(e);
    } catch (InstantiationException e) {
      throw new RuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  private static void loadSystemProperties() {
    Map<String, String> map = ROOT.systemProperties;
    for (Map.Entry<String, String> entry : map.entrySet()) {
      if (!System.getProperties().containsKey(entry.getKey())) {
        System.setProperty(entry.getKey(), entry.getValue());
      }
    }
  }


  /**
   * Gets the current Configuration Path.
   *
   * @return The current Path.
   */
  public static String getPath() {
    return configPath;
  }

  public static String getFileName() {
    return Path.of(configPath, CONFIG_FILE).toString();
  }

  public static Root getRoot() {
    return ROOT;
  }

  public static List<Object> getWorkers() {
    return workers;
  }

  /**
   * Loads a config object frm the service yaml.
   *
   * @param clazz The class to load
   * @param <T>   The class template argument.
   * @return An instance of the config object.
   * @throws IOException
   */
  public static <T> T getConfig(Class<T> clazz) {
    for (Object cfg : configs) {
      if (cfg.getClass().isAssignableFrom(clazz)) {
        return (T) cfg;
      }
    }
    try {
      T result = (T) Mapper.INSTANCE.readStringValue(new File(getFileName()), clazz);
      configs.add(result);
      return result;
    } catch (IOException e) {
      throw new RuntimeException(new NoSuchElementException(clazz.getCanonicalName()));
    }
  }

  public static <T> T getWorker(Class<T> clazz) {
    List<Object> list = getWorkers();
    for (Object item : list) {



      if (clazz.isInterface()) {
        Class currentClass = item.getClass();
        while (currentClass != null) {
          Type[] interfaces = currentClass.getInterfaces();
          for (Type type : interfaces) {
            if (type.equals((Type) clazz)) {
              return (T) item;
            }
          }
          currentClass = currentClass.getSuperclass();
        }
      } else if (item.getClass().isAssignableFrom(clazz)) {
        return (T) item;
      }

    }
    throw new NoSuchElementException(clazz.getName());
  }

  public static <T> T getEntity(Class<T> clazz, int id) {

    Session session = HibernateUtil.getSessionFactory().openSession();
    return (T) session.get(clazz, id);
  }


}
