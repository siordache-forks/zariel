package org.metalscraps.eso.lang.lib.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.metalscraps.eso.lang.lib.bean.ID;
import org.metalscraps.eso.lang.lib.bean.PO;
import org.metalscraps.eso.lang.lib.bean.ToCSVConfig;
import org.metalscraps.eso.lang.lib.config.AppConfig;
import org.metalscraps.eso.lang.lib.config.AppWorkConfig;
import org.metalscraps.eso.lang.lib.config.SourceToMapConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.filechooser.FileSystemView;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

@SuppressWarnings({"WeakerAccess", "unused"})
public class Utils {
    private static final Logger logger = LoggerFactory.getLogger(Utils.class);

    private static HashMap<String, String> versionMap = new HashMap<>();
    private static HashMap<String, ArrayList<String>> projectMap = new HashMap<>();
    public static String getLatestVersion(String projectName) {
        if(versionMap.containsKey(projectName)) return versionMap.get(projectName);
        HttpRequest request = getDefaultRestClient(AppConfig.ZANATA_DOMAIN+"rest/projects/p/"+projectName);

        JsonNode jsonNode = getBodyFromHTTPsRequest(request);
        String[] serverVer = null;
        for (JsonNode node : jsonNode.get("iterations")) {
            if(node.get("status").toString().equalsIgnoreCase("\"ACTIVE\"") ) { // && temp > version
                String[] tempVer = node.get("id").asText().split("\\.");
                if(serverVer == null) {
                    serverVer = tempVer;
                    continue;
                }
                for(int i=0; i<3; i++) {
                    if(Integer.parseInt(tempVer[i]) > Integer.parseInt(serverVer[i])) {
                        serverVer = tempVer;
                        break;
                    }
                }
            }
        }
        if(serverVer == null) serverVer = new String[] {"0.0.0"};
        versionMap.put(projectName, String.join(".", serverVer));
        return versionMap.get(projectName);
    }

    public static Path getESOLangDir() {
        return FileSystemView.getFileSystemView().getDefaultDirectory().toPath().resolve("Elder Scrolls Online/live/AddOns/gamedata/lang");
    }

    public static Path getESODir() {
        return FileSystemView.getFileSystemView().getDefaultDirectory().toPath().resolve("Elder Scrolls Online/");
    }

    public static JsonNode getBodyFromHTTPsRequest(HttpRequest request){

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = null;
        try { response = client.send(request, HttpResponse.BodyHandlers.ofString()); }
        catch (IOException | InterruptedException e) { e.printStackTrace(); }

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = null;

        var body = Objects.requireNonNull(response).body();
        logger.trace(body);

        try { jsonNode = objectMapper.readTree(body); }
        catch (IOException e) { e.printStackTrace(); }
        return jsonNode;
    }

    public static ArrayList<String> getFileNames(String projectName){
        ArrayList<String> fileNames = new ArrayList<>();
        var request = getDefaultRestClient(AppConfig.ZANATA_DOMAIN+"rest/projects/p/"+ projectName +"/iterations/i/" +Utils.getLatestVersion(projectName)+"/r");
        JsonNode jsonNode = getBodyFromHTTPsRequest(request);

        for (Iterator<JsonNode> it = jsonNode.elements(); it.hasNext(); ) {
            JsonNode node = it.next();
            String Trim = node.get("name").toString().replaceAll("^\"|\"$", "");
            fileNames.add(Trim);
        }

        return fileNames;
    }

    public static void downloadPOs(AppWorkConfig appWorkConfig){
        LocalTime timeTaken = LocalTime.now();
        downloadPO(appWorkConfig, "ESO-item");
        downloadPO(appWorkConfig, "ESO-skill");
        downloadPO(appWorkConfig, "ESO-system");
        downloadPO(appWorkConfig, "ESO-book");
        downloadPO(appWorkConfig, "ESO-story");
        logger.info("총 " + timeTaken.until(LocalTime.now(), ChronoUnit.SECONDS) + "초");
    }

    public static void downloadPO(AppWorkConfig appWorkConfig, String projectName) {
        Path pPO = null;

        try {

            final String url = AppConfig.ZANATA_DOMAIN+"rest/file/translation/"+projectName+"/"+Utils.getLatestVersion(projectName)+"/ko/po?docId=";
            final Path PODirectory = appWorkConfig.getBaseDirectoryToPath().resolve("PO_" + appWorkConfig.getToday());
            final ArrayList<String> fileNames = getFileNames(projectName);
            appWorkConfig.setPODirectoryToPath(PODirectory);
            if(!Files.exists(PODirectory)) Files.createDirectories(PODirectory);


            for (String fileName : fileNames) {

                // 우리가 사용하는 데이터 아님.
                if (fileName.equals("00_EsoUI_Client") || fileName.equals("00_EsoUI_Pregame")) continue;

                LocalTime ltStart = LocalTime.now();
                String fileURL = url+fileName;
                fileURL = fileURL.replace(" ", "%20");

                pPO = PODirectory.resolve(fileName+".po");

                logger.trace("download zanata file  ["+fileName+"] to local ["+pPO+"] ");
                if (!Files.exists(pPO)) {
                    var server = Channels.newChannel(new URL(fileURL).openStream());
                    var out = FileChannel.open(pPO, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    out.transferFrom(server, 0, Long.MAX_VALUE);
                }

                LocalTime ltEnd = LocalTime.now();
                logger.trace(" " + ltStart.until(ltEnd, ChronoUnit.SECONDS) + "초");
            }

        } catch (IOException e) {
            if(e.getMessage().contains("Premature EOF")) {
                logger.warn("EOF 재시도");
                if(Files.exists(pPO)) try { Files.delete(pPO); } catch (IOException e1) { e1.printStackTrace(); }
                try { Thread.sleep(1800000); } catch (InterruptedException e1) {  e1.printStackTrace(); }
                Utils.downloadPO(appWorkConfig, projectName);
            }
            else e.printStackTrace();
        } catch (Exception e) { logger.error(e.getMessage()); e.printStackTrace(); }

    }

    public static long CRC32(Path p) {
        Checksum crc = new CRC32();
        try { if(Files.notExists(p) || Files.size(p) <= 0) return -1; }
        catch (IOException e) { e.printStackTrace(); }

        try(BufferedInputStream in = new BufferedInputStream(Files.newInputStream(p))) {
            byte[] buffer = new byte[32768];
            int length;
            while ((length = in.read(buffer)) >= 0) crc.update(buffer, 0, length);
        } catch (IOException e) { e.printStackTrace(); }
        return crc.getValue();
    }

    public static void processRun(Path baseDirectory, String command) throws IOException, InterruptedException { processRun(baseDirectory, command, ProcessBuilder.Redirect.INHERIT); }

    public static void processRun(Path baseDirectory, String command, ProcessBuilder.Redirect redirect) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder()
                .directory(baseDirectory.toFile())
                .command(command.split("\\s+"))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(redirect);
        pb.start().waitFor();
    }


    public static void makeCSV(Path path, ToCSVConfig toCSVConfig, ArrayList<PO> poList) {
        StringBuilder sb = new StringBuilder("\"Location\",\"Source\",\"Target\"\n");
        for (PO p : poList) {
            sb.append(p.toCSV(toCSVConfig));
        }
        try {
            Files.writeString(path, sb.toString(), AppConfig.CHARSET);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void convertKO_PO_to_CN(AppWorkConfig appWorkConfig) {

        var fileList = Utils.listFiles(appWorkConfig.getPODirectoryToPath(), "po");

        try {
            for (Path file : fileList) {
                Path po2 = Paths.get(file.toString()+"2");
                if(!Files.exists(po2)) Files.writeString(po2, Utils.KOToCN( Files.readString(file, AppConfig.CHARSET)));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String KOToCN(String string) {
        char[] c = string.toCharArray();
        for(int i=0; i < c.length; i++) if (c[i] >= 0xAC00 && c[i] <= 0xEA00) c[i] -= 0x3E00;
        return new String(c);
    }

    public static String CNtoKO(String string) {
        char[] c = string.toCharArray();
        for(int i=0; i < c.length; i++) if (c[i] >= 0x6E00 && c[i] <= 0xAC00) c[i] += 0x3E00;
        return new String(c);
    }


    public static void replaceStringFromMap(StringBuilder stringBuilder, Map<String, ?> map) {
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            String key = entry.getKey();
            Object rawValue = entry.getValue();
            String value = rawValue instanceof PO ? ((PO) rawValue).getTarget() : rawValue instanceof String ? (String) rawValue : key;

            int start = stringBuilder.indexOf(key, 0);
            while (start > -1) {
                int end = start + key.length();
                int nextSearchStart = start + value.length();
                stringBuilder.replace(start, end, value);
                start = stringBuilder.indexOf(key, nextSearchStart);
            }
        }
    }

    public static HashMap<String, PO> sourceToMap(SourceToMapConfig config) {

        if(config.getPattern() == null) {
            var ext = getExtension(config.getPath());
            if(ext.equals("po") || ext.equals("po2")) config.setPattern(AppConfig.POPattern);
            else if(ext.equals("csv")) config.setPattern(AppConfig.CSVPattern);
        }

        HashMap<String, PO> poMap = new HashMap<>();
        String fileName = getName(config.getPath());
        String source = parseSourceToMap(config);

        Matcher m = config.getPattern().matcher(source);
        boolean isPOPattern = (config.getPattern() == AppConfig.POPattern);
        while (m.find()) {
            PO po = new PO(m.group(2), m.group(6), m.group(7)).wrap(config.getPrefix(), config.getSuffix(), config.getPoWrapType());
            //po.setFileName(FileNames.fromString(fileName));
            po.setStringFileName(fileName);
            if(isPOPattern && m.group(1) != null && m.group(1).equals("#, fuzzy")) po.setFuzzy(true);
            poMap.put(m.group(config.getKeyGroup()), po);
        }

        return poMap;
    }

    private static String parseSourceToMap(SourceToMapConfig config) {

        String source = null;
        try {
            source = Files.readString(config.getPath(), AppConfig.CHARSET);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (config.isToLowerCase()) source = Objects.requireNonNull(source).toLowerCase();

        if (config.isProcessText()) {
            if (config.isProcessItemName()) source = Objects.requireNonNull(source).replaceAll("\\^[\\w]+", ""); // 아이템 명 뒤의 기호 수정
            source = Objects.requireNonNull(source).replaceAll("msgid \"\\\\+\"\n", "msgid \"\"\n") // "//" 이런식으로 되어있는 문장 수정. Extractor 에서 에러남.
                    .replaceAll("msgstr \"\\\\+\"\n", "msgstr \"\"\n") // "//" 이런식으로 되어있는 문장 수정. Extractor 에서 에러남.
                    .replaceAll("\\\\\"", "\"\"") // \" 로 되어있는 쌍따옴표 이스케이프 변환 "" 더블-더블 쿼테이션으로 이스케이프 시켜야함.
                    .replaceAll("\\\\\\\\", "\\\\"); // 백슬래쉬 두번 나오는거 ex) ESOUI\\ABC\\DEF 하나로 고침.

        }
        return source;

    }

    private void processRun(String command, File directory) throws IOException, InterruptedException { processRun(command, ProcessBuilder.Redirect.INHERIT, directory); }

    private void processRun(String command, @SuppressWarnings("SameParameterValue") ProcessBuilder.Redirect redirect, File directory) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder()
                .directory(directory)
                .command((command).split("\\s+"))
                .redirectError(redirect)
                .redirectOutput(redirect);
        pb.start().waitFor();
    }

    public static Properties setConfig(Path appPath, Path configPath, Map<String, String> config) {

        logger.info("앱 설정 폴더 확인");
        if(Files.notExists(appPath)) {
            logger.info("폴더 존재하지 않음 생성.");
            try {
                Files.createDirectories(appPath);
                logger.info(appPath + " 생성 성공");
            } catch (IOException e) {
                logger.error("설정 폴더 생성 실패. 앱 종료");
                e.printStackTrace();
                System.exit(0);
            }
        }

        logger.info("설정 파일 확인");

        if(Files.notExists(configPath)) {
            logger.info("설정 존재하지 않음 생성.");
            try {
                Files.createFile(configPath);
                if(Files.exists(configPath)) logger.info(configPath + " 생성 성공");
                else {
                    logger.error("설정 생성 실패. 앱 종료");
                    System.exit(0);
                }
            } catch (IOException e) {
                e.printStackTrace();
                logger.error("설정 생성 실패. 앱 종료");
                System.exit(0);
            }
        }

        Properties properties = new Properties();
        try {
            if (Files.exists(configPath) && Files.size(configPath) > 0) try(var fis = Files.newInputStream(configPath)) { properties.load(fis); } catch (Exception e) { e.printStackTrace(); }
            else logger.info("설정 데이터 없음. 초기화");
        } catch (IOException e) {
            e.printStackTrace();
        }

        config.forEach(properties::putIfAbsent);
        try(var fos = Files.newOutputStream(configPath)) {
            properties.store(fos, "");
        } catch (Exception e) {
            logger.error(e.getMessage());
            e.printStackTrace();
        }

        return properties;
    }

    private static HttpRequest getDefaultRestClient(String domain) {
        return HttpRequest.newBuilder().uri(URI.create(domain)).header("Accept","application/json").build();
    }

    public static ArrayList<String> getDocuments(String projectName) {
        var list = projectMap.get(projectName);
        if(list.size() == 0) list.addAll(getFileNames(projectName));
        return list;
    }

    public static HashMap<String, ArrayList<String>> getProjectMap() {
        if(projectMap.size() == 0) {
            logger.info("rest/projects");
            var request = getDefaultRestClient(AppConfig.ZANATA_DOMAIN+"rest/projects");

            JsonNode jsonNode = getBodyFromHTTPsRequest(request);

            for(var x : jsonNode) {
                var id = String.valueOf(x.get("id")).replace("\"","");
                if(id.startsWith("ESO-") && String.valueOf(x.get("status")).equals("\"ACTIVE\"")) projectMap.put(id, getFileNames(id));
            }
        }

        for(var x : projectMap.keySet()) getDocuments(x);
        return projectMap;
    }

    public static String getProjectNameByDocument(ID id) throws Exception {
        if(!id.isFileNameHead()) throw new ID.NotFileNameHead();
        for(var x : getProjectMap().entrySet())
            for(var y : x.getValue())
                if(y.equalsIgnoreCase(id.getHead())) {
                    id.setHead(y);
                    return x.getKey();
                }
        throw new Exception("프로젝트 못찾음 /" + id.toString());
    }


    public static Map<String, PO> getMergedPOtoMap(Collection fileList) {
        var map = new HashMap<String, PO>();
        var config = new SourceToMapConfig();

        for (var x : fileList) {
            String fileName = "";
            String ext = "";
            if(x instanceof File) {
                var file = (File) x;
                fileName = getName(file.toPath());
                ext = getExtension(file.toPath());
                config.setFile(file);
            } else if(x instanceof Path) {
                var path = (Path) x;
                fileName = getName(path);
                ext = getExtension(path);
                config.setPath(path);
            } else {
                logger.error("알 수 없는 컬렉션");
                logger.error(x.getClass().toString());
            }

            if(ext.equals("csv")) config.setPattern(AppConfig.CSVPattern);
            else if(ext.startsWith("po")) config.setPattern(AppConfig.POPattern);

            // pregame 쪽 데이터
            if (fileName.equals("00_EsoUI_Client") || fileName.equals("00_EsoUI_Pregame")) continue;

            map.putAll(Utils.sourceToMap(config));
            logger.trace(x.toString());
        }

        map.get("242841733-0-54340").setTarget(Utils.KOToCN("매지카 물약"));

        var xErrors = Arrays.asList(
                "307","337","339","340","342","343","345","346","348","349",
                "351","352","354","355","357","358","360","361","363","364"
        );
        xErrors.forEach(o->map.get("41714900-0+"+o).setTarget(""));

        return map;
    }

    public static ArrayList<PO> getMergedPO(Collection fileList) {
        var sourceList = new ArrayList<>(getMergedPOtoMap(fileList).values());
        sourceList.sort(PO.comparator);
        return sourceList;
    }

    public static void makeCSVwithLog(Path path, ToCSVConfig csvConfig, ArrayList<PO> sourceList) {
        LocalTime timeTaken = LocalTime.now();
        Utils.makeCSV(path, csvConfig, sourceList);
        logger.info(path.getFileName() + " " + timeTaken.until(LocalTime.now(), ChronoUnit.SECONDS) + "초");
    }

    public static String getName(Path path) {
        if(Files.isDirectory(path)) logger.error("파일 아님 " + path.toAbsolutePath());
        var x = path.getFileName().toString();
        return x.substring(0, x.lastIndexOf('.'));
    }

    public static String getExtension(Path path) {
        if(Files.isDirectory(path)) logger.error("파일 아님 " + path.toAbsolutePath());
        var x = path.getFileName().toString();
        return x.substring(x.lastIndexOf('.')+1);
    }

    public static Collection<Path> listFiles(Path path, String ext) {

        try {
            return Files.list(path).filter(x -> !Files.isDirectory(x) && getExtension(x).equals(ext)).collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new ArrayList<>();
    }

}