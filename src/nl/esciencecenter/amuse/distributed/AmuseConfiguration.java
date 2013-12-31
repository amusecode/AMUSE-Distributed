/*
 * Copyright 2013 Netherlands eScience Center
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.esciencecenter.amuse.distributed;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Class that holds configuration information for a Amuse installation. Loaded from the config.mk file generated by AMUSE
 * 
 * @author Niels Drost
 */
public class AmuseConfiguration {

    private final File amuseHome;

    private final Map<String, String> config;

    private void parseConfig(BufferedReader reader) throws DistributedAmuseException, IOException {
        while (true) {
            String line = reader.readLine();

            if (line == null) {
                return;
            } else if (line.trim().isEmpty() || line.startsWith("#") || line.startsWith("export")) {
                //SKIP
            } else {
                String[] elements = line.split("=", 2);

                if (elements.length != 2) {
                    throw new DistributedAmuseException("Could not parse config option \"" + line + "\"");
                }

                String option = elements[0].trim();
                String value = elements[1].trim();

                config.put(option, value);
            }
        }
    }

    public AmuseConfiguration(String amuseHome, InputStream in) throws DistributedAmuseException {
        this.amuseHome = new File(amuseHome);
        config = new HashMap<String, String>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            parseConfig(reader);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }

    public AmuseConfiguration(File amuseHome) throws DistributedAmuseException {
        this.amuseHome = amuseHome;
        config = new HashMap<String, String>();

        File configFile = new File(amuseHome, "config.mk");

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            parseConfig(reader);

        } catch (FileNotFoundException e) {
            throw new DistributedAmuseException("cannot find config file: " + configFile, e);
        } catch (IOException e) {
            throw new DistributedAmuseException("cannot read config file: " + configFile, e);
        }
    }

    String getConfigOption(String name) throws DistributedAmuseException {
        String result = config.get(name);

        if (result == null) {
            throw new DistributedAmuseException("configuration option not found: \"" + name + "\"");
        }

        return result;
    }

    public File getAmuseHome() {
        return amuseHome;
    }

    public boolean isMpiexecEnabled() throws DistributedAmuseException {
        return getConfigOption("MPIEXEC_ENABLED").equals("yes");
    }

    public String getMpiexec() throws DistributedAmuseException {
        return getConfigOption("MPIEXEC");
    }

    public boolean isJavaEnabled() throws DistributedAmuseException {
        return getConfigOption("JAVA_ENABLED").equals("yes");
    }

    public String getJava() throws DistributedAmuseException {
        return getConfigOption("JAVA");
    }
}
