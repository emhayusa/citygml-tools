/*
 * citygml-tools - Collection of tools for processing CityGML files
 * https://github.com/citygml4j/citygml-tools
 *
 * citygml-tools is part of the citygml4j project
 *
 * Copyright 2018-2020 Claus Nagel <claus.nagel@gmail.com>
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

package org.citygml4j.tools.command;

import org.citygml4j.builder.jaxb.CityGMLBuilder;
import org.citygml4j.builder.jaxb.CityGMLBuilderException;
import org.citygml4j.geometry.BoundingBox;
import org.citygml4j.model.citygml.CityGML;
import org.citygml4j.model.citygml.appearance.Appearance;
import org.citygml4j.model.gml.feature.AbstractFeature;
import org.citygml4j.tools.CityGMLTools;
import org.citygml4j.tools.common.helper.CityModelInfoHelper;
import org.citygml4j.tools.common.helper.ImplicitGeometryReader;
import org.citygml4j.tools.common.log.Logger;
import org.citygml4j.tools.heightchanger.ChangeHeightException;
import org.citygml4j.tools.heightchanger.HeightChanger;
import org.citygml4j.tools.heightchanger.HeightMode;
import org.citygml4j.tools.option.CityGMLOutputOptions;
import org.citygml4j.tools.option.InputOptions;
import org.citygml4j.tools.option.LoggingOptions;
import org.citygml4j.tools.util.ObjectRegistry;
import org.citygml4j.tools.util.Util;
import org.citygml4j.xml.io.reader.CityGMLReadException;
import org.citygml4j.xml.io.reader.CityGMLReader;
import org.citygml4j.xml.io.writer.CityGMLWriteException;
import org.citygml4j.xml.io.writer.CityModelInfo;
import org.citygml4j.xml.io.writer.CityModelWriter;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@CommandLine.Command(name = "change-height",
        description = "Changes the height values of city objects by a given offset.",
        versionProvider = CityGMLTools.class,
        mixinStandardHelpOptions = true,
        showAtFileInUsageHelp = true)
public class ChangeHeightCommand implements CityGMLTool {
    @CommandLine.Option(names = "--offset", paramLabel = "<double>", required =  true, description = "Offset to add to height values.")
    private double offset;

    @CommandLine.Option(names = "--height-mode", paramLabel = "<mode>", description = "Height mode: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).")
    private HeightMode heightMode = HeightMode.RELATIVE;

    @CommandLine.Option(names = "--overwrite-files", description = "Overwrite input file(s).")
    private boolean overwriteInputFiles;

    @CommandLine.Mixin
    private CityGMLOutputOptions cityGMLOutput;

    @CommandLine.Mixin
    private InputOptions input;

    @CommandLine.Mixin
    private LoggingOptions logging;

    @Override
    public Integer call() throws Exception {
        Logger log = Logger.getInstance();
        String fileNameSuffix = "_adapted-height";

        CityGMLBuilder cityGMLBuilder = ObjectRegistry.getInstance().get(CityGMLBuilder.class);
        ImplicitGeometryReader implicitGeometryReader = new ImplicitGeometryReader(cityGMLBuilder);

        log.debug("Searching for CityGML input files.");
        List<Path> inputFiles;
        try {
            inputFiles = new ArrayList<>(Util.listFiles(input.getFile(), "**.{gml,xml}", fileNameSuffix));
            log.info("Found " + inputFiles.size() + " file(s) at '" + input.getFile() + "'.");
        } catch (IOException e) {
            log.warn("Failed to find file(s) at '" + input.getFile() + "'.");
            return 0;
        }

        for (int i = 0; i < inputFiles.size(); i++) {
            Path inputFile = inputFiles.get(i);
            log.info("[" + (i + 1) + "|" + inputFiles.size() + "] Processing file '" + inputFile.toAbsolutePath() + "'.");

            Path outputFile;
            if (!overwriteInputFiles) {
                outputFile = Util.addFileNameSuffix(inputFile, fileNameSuffix);
                log.info("Writing output to file '" + outputFile.toAbsolutePath() + "'.");
            } else {
                outputFile = inputFile.resolveSibling("tmp-" + UUID.randomUUID());
                log.debug("Writing temporary output file '" + outputFile.toAbsolutePath() + "'.");
            }

            HeightChanger heightChanger = HeightChanger.defaults()
                    .withHeightMode(heightMode);

            if (heightMode == HeightMode.ABSOLUTE) {
                log.debug("Reading implicit geometries from input file.");
                try {
                    heightChanger.withImplicitGeometries(implicitGeometryReader.readImplicitGeometries(inputFile));
                } catch (CityGMLReadException e) {
                    log.error("Failed to parse implicit geometries.", e);
                    return 1;
                }
            }

            log.debug("Reading city objects from input file and changing height values.");

            try (CityGMLReader reader = input.createCityGMLReader(inputFile, input.createSkipFilter("CityModel"));
                 CityModelWriter writer = cityGMLOutput.createCityModelWriter(outputFile)) {
                boolean isInitialized = false;

                while (reader.hasNext()) {
                    CityGML cityGML = reader.nextFeature();

                    // write city model
                    if (!isInitialized) {
                        CityModelInfo cityModelInfo = CityModelInfoHelper.getCityModelInfo(cityGML, reader.getParentInfo());

                        if (cityModelInfo.isSetBoundedBy() && cityModelInfo.getBoundedBy().isSetEnvelope()) {
                            BoundingBox bbox = cityModelInfo.getBoundedBy().getEnvelope().toBoundingBox();
                            if (bbox != null) {
                                double correction = heightMode == HeightMode.ABSOLUTE ?
                                        offset - bbox.getLowerCorner().getZ() : offset;

                                bbox.getLowerCorner().setZ(bbox.getLowerCorner().getZ() + correction);
                                bbox.getUpperCorner().setZ(bbox.getUpperCorner().getZ() + correction);
                                cityModelInfo.getBoundedBy().setEnvelope(bbox);
                            }
                        }

                        writer.setCityModelInfo(cityModelInfo);
                        writer.writeStartDocument();
                        isInitialized = true;
                    }

                    if (cityGML instanceof AbstractFeature) {
                        AbstractFeature feature = (AbstractFeature) cityGML;

                        try {
                            if (!(feature instanceof Appearance))
                                heightChanger.changeHeight(feature, offset);
                        } catch (ChangeHeightException e) {
                            log.warn("Not changing height for " + cityGML.getCityGMLClass() + " with gml:id '" +
                                    feature.getId() + "'.", e);
                        }

                        writer.writeFeatureMember(feature);
                    }
                }

            } catch (CityGMLBuilderException | CityGMLReadException e) {
                log.error("Failed to read city objects.", e);
                return 1;
            } catch (CityGMLWriteException e) {
                log.error("Failed to write city objects.", e);
                return 1;
            }

            if (overwriteInputFiles) {
                try {
                    log.debug("Replacing input file with temporary file.");
                    Files.delete(inputFile);
                    Files.move(outputFile, outputFile.resolveSibling(inputFile.getFileName()));
                } catch (IOException e) {
                    log.error("Failed to overwrite input file.", e);
                    return 1;
                }
            }
        }

        return 0;
    }
}
