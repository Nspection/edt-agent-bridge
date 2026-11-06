package com.codepilot1c.core.edt.development;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public class RunnerScenarioParamsTest {
    @Test public void selectsFeatureWithoutEditingBaseAndProducesIdempotentPath() throws Exception {
        Path root=Files.createTempDirectory("runner-scenario-"),base=root.resolve("base.json"),feature=root.resolve("test.feature");Files.writeString(base,"\uFEFF{\"КлиентыТестирования\":[{\"Имя\":\"Client\"}]}",StandardCharsets.UTF_8);Files.writeString(feature,"#language: ru\nСценарий: Тест\n  Дано условие\n",StandardCharsets.UTF_8);byte[] before=Files.readAllBytes(base);
        Path selected=RunnerScenarioParams.selected(root,base,List.of(feature.toString()),true);assertArrayEquals(before,Files.readAllBytes(base));assertEquals(selected,RunnerScenarioParams.selected(root,base,List.of(feature.toString()),true));RunnerScenarioParams.validate(selected,true);assertTrue(Files.readString(selected).contains("ВыполнитьСценарии"));Files.writeString(selected,"{}");assertThrows(IllegalStateException.class,()->RunnerScenarioParams.selected(root,base,List.of(feature.toString()),true));
    }
    @Test public void missingSelectionAndMultipleVisualScenariosAreRefusedBeforeLaunching() throws Exception {
        Path root=Files.createTempDirectory("runner-invalid-"),base=root.resolve("base.json"),feature=root.resolve("test.feature");Files.writeString(base,"{\"КлиентыТестирования\":[{\"Имя\":\"Client\"}]}");assertThrows(IllegalArgumentException.class,()->RunnerScenarioParams.validate(base,true));assertThrows(IllegalArgumentException.class,()->RunnerScenarioParams.selected(root,base,List.of(root.resolve("missing.feature").toString()),true));Files.writeString(feature,"Scenario: First\nScenario: Second\n");assertThrows(IllegalArgumentException.class,()->RunnerScenarioParams.selected(root,base,List.of(feature.toString()),true));RunnerScenarioParams.validate(RunnerScenarioParams.selected(root,base,List.of(feature.toString()),false),false);
    }
}
