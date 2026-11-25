// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.PlatformUI;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.codepilot1c.core.edt.development.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;

/** Local approval and single-use receipts for one exact registered server test base. */
public final class DtScenarioService {
    private static final Object LOCK = new Object();
    private static final Gson JSON = new Gson();
    private static final long TTL_MS = Duration.ofHours(2).toMillis();
    private static final Set<String> PLAN_FIELDS = Set.of("project", "base_uuid", "runtime_version",
            "server_address", "server_infobase", "dt_path", "start_testclient", "update_mode", "accept_legal_updates", "restored_base_choice", "allow_passwordless_restore_with_trace");
    private final NativeInfobaseGateway gateway = new NativeInfobaseGateway();
    private final Path root = gateway.stateRoot().resolve("dt-scenarios");

    public Map<String,Object> preview(Map<String,Object> args) throws Exception {
        if (!PLAN_FIELDS.containsAll(args.keySet())) throw new IllegalArgumentException("Unsupported scenario field");
        var restore = new LinkedHashMap<String,Object>();
        String mode = Objects.toString(args.get("update_mode"),"restore_dt");
        if (!Set.of("restore_dt","full","incremental").contains(mode))
            throw new IllegalArgumentException("Invalid scenario update mode");
        boolean restoreDt = "restore_dt".equals(mode);
        if (!restoreDt && !Collections.disjoint(args.keySet(),Set.of("dt_path","server_address","server_infobase","runtime_version")))
            throw new IllegalArgumentException("DT and server import fields apply only to restore_dt");
        if (args.containsKey("allow_passwordless_restore_with_trace")
                && (!restoreDt || !(args.get("allow_passwordless_restore_with_trace") instanceof Boolean)))
            throw new IllegalArgumentException("Passwordless restore with trace applies only to restore_dt");
        String restoredBaseChoice = restoredBaseChoice(args, restoreDt);
        String project = RuntimeExecutionPlan.required(args,"project");
        for (var key : restoreDt ? List.of("project","runtime_version","server_address","server_infobase","dt_path") : List.<String>of()) {
            restore.put(key, RuntimeExecutionPlan.required(args,key));
        }
        String uuid = RuntimeExecutionPlan.required(args,"base_uuid");
        if (!UUID.fromString(uuid).toString().equals(uuid)) throw new IllegalArgumentException("Canonical UUID required");
        restore.putAll(Map.of("kind","server","server_operation","restore","restore_existing",true,
                "set_primary",true,"replace_primary",false,"update_from_project",true));
        if (args.containsKey("start_testclient") && !(args.get("start_testclient") instanceof Boolean))
            throw new IllegalArgumentException("start_testclient must be boolean");
        boolean testClient = Boolean.TRUE.equals(args.get("start_testclient"));
        if (args.containsKey("accept_legal_updates") && !(args.get("accept_legal_updates") instanceof Boolean))
            throw new IllegalArgumentException("accept_legal_updates must be boolean");
        if (Boolean.TRUE.equals(args.get("accept_legal_updates")) && !testClient)
            throw new IllegalArgumentException("License confirmation applies only to an approved TestClient");
        var registration = new RegisteredInfobaseGateway();
        var binding = registration.describeScope(project,uuid);
        if (registration.describe(project,uuid).get("owners") instanceof List<?> owners
                && owners.stream().anyMatch(owner->!project.equals(owner)))
            throw new IllegalStateException("Scenario base belongs to another project");
        var plan = restoreDt ? new ServerInfobaseProvisionService().preview(restore) : Map.<String,Object>of();
        if (restoreDt && !uuid.equals(plan.get("base_uuid"))) throw new IllegalStateException("Registered server UUID differs from the requested base");
        if (!uuid.equals(binding.get("primary_uuid"))) throw new IllegalStateException("Scenario requires the exact existing primary application");
        String digest = restoreDt ? sha(InfobasePaths.absolute((String)restore.get("dt_path"))) : "";
        var scope = testClient ? new DirectTestClientService().consentScope(Map.of("project",project,"client_kind","thin")) : Map.of();
        if (testClient && !uuid.equals(scope.get("base_uuid"))) throw new IllegalStateException("TestClient target changed");
        var result = new LinkedHashMap<String,Object>();
        result.put("request", new LinkedHashMap<>(args));
        result.put("update_mode",mode);
        if (restoreDt) result.put("restored_base_choice",restoredBaseChoice);
        result.put("approved_target_identity_sha256",binding.get("target_identity_sha256"));
        if (restoreDt) {
            result.put("restore_args",restore);
            result.put("restore_preview_sha256",plan.get("preview_sha256"));
        }
        result.put("binding_sha256",TaskSessionService.fingerprint(binding));
        result.put("dt_sha256",digest);
        result.put("testclient_scope_sha256",TaskSessionService.fingerprint(scope));
        var trace=gateway.platformCommandTrace();
        result.put("trace",trace.name());
        boolean tracePasswordless=trace==NativeInfobaseGateway.PlatformTrace.ACTIVE
                && Boolean.TRUE.equals(args.get("allow_passwordless_restore_with_trace"));
        if(tracePasswordless)result.put("trace_passwordless_restore",true);
        var steps=new ArrayList<>(restoreDt ? List.of("backup","restore_dt","full_to_equal") : List.of(mode+"_to_equal"));
        if (tracePasswordless) {
            steps=new ArrayList<>(List.of("verify_passwordless_backup_access","backup","restore_dt",
                    "pause_before_post_restore_access","continue_full_after_secure_access"));
        }
        if (testClient) steps.add("start_testclient");
        if (testClient && restoreDt && !"manual".equals(restoredBaseChoice)) steps.add("confirm_restored_base_if_present");
        if (Boolean.TRUE.equals(args.get("accept_legal_updates"))) steps.add("confirm_legal_updates_if_present");
        result.put("steps",steps);
        result.put("plan_sha256",TaskSessionService.fingerprint(result));
        return result;
    }

    static String restoredBaseChoice(Map<String,Object> args, boolean restoreDt) {
        if (!args.containsKey("restored_base_choice")) return restoreDt ? "copy" : "";
        if (!restoreDt) throw new IllegalArgumentException("restored_base_choice applies only to restore_dt");
        if (!(args.get("restored_base_choice") instanceof String choice)
                || !Set.of("copy","moved","manual").contains(choice)) {
            throw new IllegalArgumentException("Invalid restored_base_choice");
        }
        return choice;
    }

    public Map<String,Object> approve(Map<String,Object> args,String expected) throws Exception {
        var plan = preview(args);
        check(plan,expected);
        if (!PlatformUI.isWorkbenchRunning()) throw new IllegalStateException("EDT user approval requires an open workbench");
        var workbench = PlatformUI.getWorkbench();
        var display = workbench.getDisplay();
        boolean[] approved = {false};
        display.syncExec(() -> {
            var window = workbench.getActiveWorkbenchWindow();
            if (window == null) return;
            String body = "Только для тестовой базы.\n\n"
                    + "Проект: "+args.get("project")+"\n"
                    + "UUID базы: "+args.get("base_uuid")+"\n"
                    + ("restore_dt".equals(plan.get("update_mode")) ? "База: "+args.get("server_address")+" / "+args.get("server_infobase")
                        +"\nDT: "+args.get("dt_path")+"\nSHA-256: "+plan.get("dt_sha256")+"\n" : "")
                    + ("restore_dt".equals(plan.get("update_mode"))
                        ? "\nРешение в диалоге копии: " + switch ((String)plan.get("restored_base_choice")) {
                            case "copy" -> "Это копия информационной базы";
                            case "moved" -> "Информационная база перемещена (может возобновить работу с внешними ресурсами)";
                            default -> "Решение вручную";
                        } + "\n" : "")
                    + "\nЭтапы: "+plan.get("steps")+"\n\n"
                    + "Восстановление заменяет данные базы содержимым DT. Обновление может изменить или удалить данные."
                    + (Boolean.TRUE.equals(args.get("accept_legal_updates")) ?
                        "\nНажимая «Да», вы подтверждаете легальность получения обновления и разрешаете TestClient передать это подтверждение. Отправка статистики сюда не входит." : "")
                    + "\nРазрешить один запуск этих действий в течение 2 часов?";
            approved[0] = new MessageDialog(window.getShell(),"Разрешение на тестовый сценарий",
                    null,body,MessageDialog.QUESTION,new String[]{"Разрешить","Отмена"},1).open()==0;
        });
        if (!approved[0]) return Map.of("state","denied");
        check(preview(args),expected);
        var receipt = new LinkedHashMap<String,Object>(plan);
        String id = UUID.randomUUID().toString();
        receipt.put("run_id",id);
        receipt.put("state","approved");
        receipt.put("expires_at_ms",System.currentTimeMillis()+TTL_MS);
        synchronized (LOCK) {write(receipt);}
        return status(id);
    }

    public Map<String,Object> status(String id) throws Exception {
        synchronized (LOCK) {
            var r = read(id);
            var out = new LinkedHashMap<String,Object>();
            for (String key : List.of("run_id","state","expires_at_ms","steps","job_id","client_id","restore_journal","reason","update_mode","restored_base_choice","restored_base_operation_id","license_stage","license_operation_id")) {
                if (r.containsKey(key)) out.put(key,r.get(key));
            }
            var request=object(r.get("request"));
            for (String key : List.of("project","base_uuid","dt_path","accept_legal_updates"))
                if(request.containsKey(key))out.put(key,request.get(key));
            boolean confirmed="restored_access_pending".equals(r.get("state"))
                    || (Boolean.TRUE.equals(r.get("trace_passwordless_restore"))
                    && r.get("restore_journal") instanceof String journal
                    && Files.isRegularFile(Path.of(journal).resolveSibling("import-confirmed"),LinkOption.NOFOLLOW_LINKS));
            if (confirmed) {
                out.put("import_completed",true);
                out.put("full_reload_required",!"restored".equals(r.get("state")));
                out.put("repeat_restore",false);
                if (!"restored".equals(r.get("state"))) out.put("operator_action","restored_access_pending".equals(r.get("state"))
                        ?"verify_restored_access_then_full_without_reimport"
                        :"inspect_original_import_claim_before_continuing");
            }
            if ("approved".equals(r.get("state")) || "restored".equals(r.get("state"))) {
                if (expired(r)) out.put("state","expired");
            }
            return out;
        }
    }

    public Map<String,Object> run(String id,String caller,Consumer<Map<String,Object>> progress) throws Exception {
        Map<String,Object> receipt;
        Map<String,Object> apply;
        synchronized (LOCK) {
            receipt = read(id);
            if (!"approved".equals(receipt.get("state"))) return status(id);
            requireUnexpired(receipt);
            var live = preview(object(receipt.get("request")));
            check(live,(String)receipt.get("plan_sha256"));
            apply = new LinkedHashMap<>();
            if ("restore_dt".equals(live.get("update_mode"))) {
                apply.putAll(object(live.get("restore_args")));
                apply.put("dry_run",false);
                apply.put("scenario_dt_sha256",live.get("dt_sha256"));
                apply.put("scenario_binding_sha256",live.get("binding_sha256"));
                apply.put("expected_preview_sha256",live.get("restore_preview_sha256"));
            }
            dispatch(receipt,"restoring");
        }
        try {
            var request=object(receipt.get("request"));
            String project=(String)request.get("project"), uuid=(String)request.get("base_uuid");
            String target=(String)receipt.get("approved_target_identity_sha256");
            var monitor=new org.eclipse.core.runtime.NullProgressMonitor();
            com.codepilot1c.core.edt.runtime.EdtRuntimeService.BeforeNativeUpdate checkBinding=()->{
                var binding=new RegisteredInfobaseGateway().describeScope(project,uuid);
                if(!Objects.equals(receipt.get("binding_sha256"),TaskSessionService.fingerprint(binding)))
                    throw new IllegalStateException("Approved project binding changed");
            };
            Map<String,Object> result=switch((String)receipt.get("update_mode")) {
                case "restore_dt" -> new ServerInfobaseProvisionService(caller).runApprovedScenario(apply,progress,
                        Boolean.TRUE.equals(receipt.get("trace_passwordless_restore")),journal->{
                            synchronized(LOCK){
                                receipt.put("restore_journal",journal.toString());
                                try{write(receipt);}catch(Exception failure){
                                    throw new IllegalStateException("Cannot persist restore journal before import",failure);
                                }
                            }
                        });
                case "incremental" -> gateway.updateIncremental(project,monitor,uuid,target,checkBinding);
                case "full" -> gateway.updateApprovedFull(project,uuid,target,monitor,checkBinding);
                default -> throw new IllegalStateException("Unknown approved update mode");
            };
            boolean equal = Boolean.TRUE.equals(result.get("configuration_synchronized"));
            synchronized (LOCK) {
                receipt.put("state",equal?"restored":Boolean.TRUE.equals(result.get("import_completed"))
                        && "restored_access_pending".equals(result.get("state"))
                            ?"restored_access_pending":"restore_requires_inspection");
                if (result.containsKey("restore_journal")) receipt.put("restore_journal",result.get("restore_journal"));
                write(receipt);
            }
        } catch (Exception | LinkageError failure) {
            synchronized (LOCK) {
                receipt.put("state","restore_requires_inspection");
                receipt.put("reason","inspect_original_job_and_restore_journal");
                write(receipt);
            }
            throw failure;
        }
        return status(id);
    }

    public Map<String,Object> continueAfterRestore(String id) throws Exception {
        Map<String,Object> receipt;
        Map<String,Object> request;
        synchronized(LOCK){
            receipt=read(id);
            if(!"restored_access_pending".equals(receipt.get("state")))return status(id);
            requireUnexpired(receipt);
            if(gateway.platformCommandTrace()!=NativeInfobaseGateway.PlatformTrace.INACTIVE)
                return Map.of("run_id",id,"state","restored_access_pending","import_completed",true,
                        "repeat_restore",false,"reason","edt_command_tracing_not_verified_inactive");
            request=object(receipt.get("request"));
            verifyContinuationBinding(request,receipt);
            requireCurrentImport(request,receipt);
            dispatch(receipt,"post_restore_full_running");
        }
        try{
            String project=(String)request.get("project"),uuid=(String)request.get("base_uuid");
            var full=gateway.updateApprovedFull(project,uuid,(String)receipt.get("approved_target_identity_sha256"),
                    new org.eclipse.core.runtime.NullProgressMonitor(),()->{
                        verifyContinuationBinding(request,receipt);
                        requireCurrentImport(request,receipt);
                        if(gateway.platformCommandTrace()!=NativeInfobaseGateway.PlatformTrace.INACTIVE)
                            throw new IllegalStateException("EDT command tracing changed before FULL");
                        var target=new ServerInfobaseTarget((String)request.get("server_address"),(String)request.get("server_infobase"));
                        var ref=gateway.serverReference(target);
                        if(!uuid.equals(ref.getUuid().toString()))
                            throw new IllegalStateException("Restored infobase registration changed");
                        var monitor=new org.eclipse.core.runtime.NullProgressMonitor();
                        var access=gateway.existingServerArguments(ref,null,null,monitor,null);
                        // EDT can reconnect its own Designer on startup, even after a confirmed import.
                        boolean disconnected=gateway.disconnectForAccessVerification(project,ref);
                        try {
                            verifyContinuationBinding(request,receipt);
                            var staging=Files.createTempDirectory(gateway.stateRoot(),"post-restore-access-");
                            gateway.exportWithExplicitTaskAccess(gateway.runtime(ref,(String)request.get("runtime_version")),
                                    ref,staging.resolve("configuration"),access);
                            var savedNow=gateway.existingServerArguments(ref,null,null,monitor,null);
                            if(access.getAccess()!=savedNow.getAccess()
                                    || !Objects.equals(access.getUsername(),savedNow.getUsername())
                                    || !Objects.equals(access.getPassword(),savedNow.getPassword()))
                                throw new IllegalStateException("Stored EDT access changed after verification");
                            verifyContinuationBinding(request,receipt);
                            if(gateway.platformCommandTrace()!=NativeInfobaseGateway.PlatformTrace.INACTIVE)
                                throw new IllegalStateException("EDT command tracing changed after access verification");
                        } catch (Exception | LinkageError failure) {
                            if (disconnected) {
                                try {
                                    verifyContinuationBinding(request,receipt);
                                    if(gateway.platformCommandTrace()==NativeInfobaseGateway.PlatformTrace.INACTIVE)
                                        gateway.reconnectAfterFailedAccessVerification(project,ref);
                                } catch (Exception | LinkageError reconnectFailure) {
                                    failure.addSuppressed(reconnectFailure);
                                }
                            }
                            throw failure;
                        }
                    });
            synchronized(LOCK){
                receipt.put("state",Boolean.TRUE.equals(full.get("configuration_synchronized"))
                        ?"restored":"post_restore_full_requires_inspection");
                write(receipt);
            }
        }catch(Exception | LinkageError failure){
            synchronized(LOCK){
                receipt.put("state","post_restore_full_requires_inspection");
                receipt.put("reason","inspect_original_full_job_without_reimport");
                write(receipt);
            }
            throw failure;
        }
        return status(id);
    }

    private void requireCurrentImport(Map<String,Object> request,Map<String,Object> receipt)throws Exception {
        var target=new ServerInfobaseTarget((String)request.get("server_address"),(String)request.get("server_infobase"));
        new ServerInfobaseProvisionService(gateway).requireCurrentConfirmedImport(target,
                Path.of(RuntimeExecutionPlan.required(receipt,"restore_journal")));
    }

    private static void verifyContinuationBinding(Map<String,Object> request,Map<String,Object> receipt)throws Exception {
        var binding=new RegisteredInfobaseGateway().describeScope((String)request.get("project"),(String)request.get("base_uuid"));
        if(!Objects.equals(receipt.get("binding_sha256"),TaskSessionService.fingerprint(binding)))
            throw new IllegalStateException("Approved project binding changed after import");
    }

    public Map<String,Object> startClient(String id) throws Exception {
        Map<String,Object> receipt;
        Map<String,Object> args;
        synchronized (LOCK) {
            receipt = read(id);
            if (!"restored".equals(receipt.get("state"))) return status(id);
            requireUnexpired(receipt);
            var request = object(receipt.get("request"));
            if (!Boolean.TRUE.equals(request.get("start_testclient"))) throw new IllegalStateException("TestClient was not approved");
            String project = (String)request.get("project"), uuid = (String)request.get("base_uuid");
            var binding = new RegisteredInfobaseGateway().describeScope(project,uuid);
            if (!Objects.equals(receipt.get("binding_sha256"),TaskSessionService.fingerprint(binding)))
                throw new IllegalStateException("Approved project binding changed");
            args = Map.of("project",project,"base_uuid",uuid,"client_kind","thin");
            dispatch(receipt,"starting_testclient");
        }
        try {
            var result = new DirectTestClientService().startApprovedDtScenario(args,id,
                    (String)receipt.get("testclient_scope_sha256"), client -> {
                        synchronized (LOCK) {
                            receipt.put("client_id",client);
                            try {write(receipt);} catch (Exception error) {throw new IllegalStateException("Cannot record reserved TestClient",error);}
                        }
                    });
            synchronized (LOCK) {
                receipt.put("state","testclient_started");
                write(receipt);
            }
            var answer = new LinkedHashMap<>(status(id));
            answer.put("client",result);
            if (Set.of("copy","moved").contains(Objects.toString(receipt.get("restored_base_choice"),"manual"))) {
                answer.put("next_call", Map.of("tool","edt.edit","arguments",Map.of(
                        "op","edt_infobase_provision","args",Map.of(
                                "action","scenario_confirm_restored_base","run_id",id))));
            }
            return answer;
        } catch (Exception failure) {
            synchronized (LOCK) {
                receipt.put("state","testclient_requires_inspection");
                write(receipt);
            }
            throw failure;
        }
    }

    private static final String RESTORED_BASE_FORM = "ОбщаяФорма.БлокировкаРаботыСВнешнимиРесурсами";

    public Map<String,Object> confirmRestoredBase(String id) throws Exception {
        RuntimeConsentPolicy.assertToolAllowed("edt_test_client");
        RuntimeConsentPolicy.assertToolAllowed("edt_form_action");
        synchronized (LOCK) {
            var receipt = read(id);
            requireUnexpired(receipt);
            if (!"testclient_started".equals(receipt.get("state"))
                    || !"restore_dt".equals(receipt.get("update_mode"))) {
                throw new IllegalStateException("A started restore scenario TestClient is required");
            }
            String choice = Objects.toString(receipt.get("restored_base_choice"), "manual");
            String element = switch (choice) {
                case "copy" -> "ЭтоКопияИнформационнойБазы";
                case "moved" -> "ИнформационнаяБазаПеремещена";
                default -> "";
            };
            if (element.isEmpty()) return Map.of("run_id",id,"state","manual_required");
            String client = (String)receipt.get("client_id");
            var service = new DirectTestClientService();
            var live = service.status(client);
            if (!Boolean.TRUE.equals(live.get("live_client_binding_verified"))
                    || !Objects.equals(live.get("base_uuid"),object(receipt.get("request")).get("base_uuid"))) {
                throw new IllegalStateException("Original scenario TestClient binding is not verified");
            }
            if (receipt.get("restored_base_operation_id") instanceof String operationId) {
                return service.result(client,operationId);
            }
            String key="dt-scenario-"+id+"-restored-base";
            var original=service.scenarioResultByKey(client,id,key);
            if(original.isPresent())return original.orElseThrow();
            var report = service.observe(Map.of("client_id",client,"view","elements"));
            var window = object(report.get("window"));
            if (!Boolean.TRUE.equals(report.get("success")) || window == null
                    || !RESTORED_BASE_FORM.equals(window.get("form_name"))) {
                return Map.of("run_id",id,"state","no_matching_restored_base_form","client_id",client);
            }
            var submitted = service.actApprovedDtScenario(id,Map.of("client_id",client,"action","command","element",element,
                    "expected_window",window.get("title"),"expected_form",RESTORED_BASE_FORM,
                    "snapshot_id",report.get("snapshot_id"),"idempotency_key",key));
            receipt.put("restored_base_operation_id",submitted.get("operation_id"));
            write(receipt);
            return submitted;
        }
    }

    private static final String LICENSE_FORM =
            "Обработка.ЛегальностьПолученияОбновлений.Форма.ПроверкаЛегальностиПолученияОбновления";

    public Map<String,Object> confirmUpdates(String id) throws Exception {
        RuntimeConsentPolicy.assertToolAllowed("edt_test_client");
        RuntimeConsentPolicy.assertToolAllowed("edt_form_action");
        synchronized (LOCK) {
            var receipt=read(id);
            requireUnexpired(receipt);
            if (!"testclient_started".equals(receipt.get("state"))
                    || !Boolean.TRUE.equals(object(receipt.get("request")).get("accept_legal_updates"))) {
                throw new IllegalStateException("License confirmation was not included in this scenario");
            }
            String client=(String)receipt.get("client_id");
            var service=new DirectTestClientService();
            var before=service.status(client);
            if (!Boolean.TRUE.equals(before.get("live_client_binding_verified"))
                    || !Objects.equals(before.get("base_uuid"),object(receipt.get("request")).get("base_uuid"))) {
                throw new IllegalStateException("Original scenario TestClient binding is not verified");
            }
            String stage=Objects.toString(receipt.get("license_stage"),"privacy");
            if (receipt.get("license_operation_id") instanceof String operationId) {
                var result=service.result(client,operationId);
                if (!verifiedLicenseStep(stage,result)) {
                    return Map.of("run_id",id,"state","inspect_original_license_operation","operation_id",operationId,"client_id",client);
                }
                if ("continue".equals(stage)) return Map.of("run_id",id,"state","accepted","client_id",client);
                stage="privacy".equals(stage)?"check":"continue";
            }
            String key="dt-scenario-"+id+"-"+stage;
            var original=service.scenarioResultByKey(client,id,key);
            if(original.isPresent()){
                var result=original.orElseThrow();
                receipt.put("license_stage",stage);
                receipt.put("license_operation_id",result.get("operation_id"));
                write(receipt);
                return result;
            }
            var report=service.observe(Map.of("client_id",client,"view","elements"));
            var window=object(report.get("window"));
            if (!Boolean.TRUE.equals(report.get("success")) || window==null || !LICENSE_FORM.equals(window.get("form_name"))) {
                return Map.of("run_id",id,"state","no_matching_license_form","client_id",client);
            }
            String action=switch(stage){case "privacy"->"uncheck";case "check"->"check";case "continue"->"command";default->throw new IllegalStateException("Invalid license stage");};
            String element=switch(stage){case "privacy"->"РазрешитьОтправкуСтатистики";case "check"->"ПодтверждениеБулево";default->"ФормаПродолжить";};
            var submitted=service.actApprovedDtScenario(id,Map.of("client_id",client,"action",action,"element",element,
                    "expected_window",window.get("title"),"expected_form",LICENSE_FORM,
                    "snapshot_id",report.get("snapshot_id"),"idempotency_key",key));
            receipt.put("license_stage",stage);
            receipt.put("license_operation_id",submitted.get("operation_id"));
            write(receipt);
            var result=new LinkedHashMap<>(submitted);
            result.put("run_id",id);
            result.put("license_stage",stage);
            return result;
        }
    }
    static boolean verifiedLicenseStep(String stage,Map<String,Object> result) {
        if(!Boolean.TRUE.equals(result.get("success"))||Boolean.TRUE.equals(result.get("outcome_unknown")))return false;
        if("continue".equals(stage)) {
            var window=object(result.get("window"));
            return window!=null && window.get("form_name") instanceof String name
                    && !name.isBlank() && !LICENSE_FORM.equals(name);
        }
        var control=object(result.get("control"));
        return control!=null && Boolean.TRUE.equals(control.get("state_verified"))
                && Boolean.valueOf("check".equals(stage)).equals(control.get("value_after"));
    }
    private void dispatch(Map<String,Object> receipt,String state) throws Exception {
        String job = BackgroundJobRegistry.currentJobId();
        if (job == null) throw new IllegalStateException("Scenario requires a tracked background job");
        receipt.put("job_id",job);
        receipt.put("state",state);
        write(receipt);
    }

    private static void check(Map<String,Object> plan,String expected) {
        if (!"INACTIVE".equals(plan.get("trace"))
                && !("ACTIVE".equals(plan.get("trace")) && Boolean.TRUE.equals(plan.get("trace_passwordless_restore"))))
            throw new IllegalStateException("EDT platform command tracing must be verified inactive");
        if (!Objects.equals(plan.get("plan_sha256"),expected)) throw new IllegalStateException("DT scenario plan changed");
    }
    private static boolean expired(Map<String,Object> r) {
        return System.currentTimeMillis() >= ((Number)r.get("expires_at_ms")).longValue();
    }
    private static void requireUnexpired(Map<String,Object> r) {
        if (expired(r)) throw new IllegalStateException("DT scenario approval expired");
    }
    private Path file(String id) throws Exception {
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid run_id");
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !root.toRealPath().equals(root.toAbsolutePath().normalize()))
            throw new IllegalStateException("Scenario journal path changed");
        Path path = root.resolve(id+".json");
        if (Files.isSymbolicLink(path)) throw new IllegalStateException("Linked scenario receipt");
        return path;
    }
    private Map<String,Object> read(String id) throws Exception {
        Path path = file(id);
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>65536)
            throw new IllegalStateException("Scenario receipt unavailable");
        Map<String,Object> r = JSON.fromJson(Files.readString(path),new TypeToken<LinkedHashMap<String,Object>>(){}.getType());
        if (!id.equals(r.get("run_id"))) throw new IllegalStateException("Scenario identity changed");
        return r;
    }
    private void write(Map<String,Object> r) throws Exception {
        Path path = file((String)r.get("run_id"));
        Path temp = Files.createTempFile(root,"dt-scenario-",".tmp");
        try {
            Files.writeString(temp,JSON.toJson(r));
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {return (Map<String,Object>)value;}
    static String sha(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            for (int n; (n=in.read(buffer))!=-1;) digest.update(buffer,0,n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
