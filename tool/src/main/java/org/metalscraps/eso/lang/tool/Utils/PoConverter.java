package org.metalscraps.eso.lang.tool.Utils;

import org.metalscraps.eso.lang.lib.bean.PO;
import org.metalscraps.eso.lang.lib.config.AppConfig;
import org.metalscraps.eso.lang.lib.config.AppWorkConfig;
import org.metalscraps.eso.lang.lib.config.SourceToMapConfig;
import org.metalscraps.eso.lang.lib.util.Utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

public class PoConverter {
    private AppWorkConfig appWorkConfig;

    public void setAppWorkConfig(AppWorkConfig appWorkConfig) {
        this.appWorkConfig = appWorkConfig;
    }

    public void translateGoogle() {

        //File file = new File("C:\\Users\\user\\Documents\\Elder Scrolls Online\\EsoKR\\PO_0203/achievement.po");

        var listFiles = Utils.listFiles(appWorkConfig.getPODirectoryToPath(), "po");
        ArrayList<PO> LtransList = new ArrayList<>();
        for (var path : listFiles) {
            ArrayList<PO> fileItems = new ArrayList<>(Utils.sourceToMap(new SourceToMapConfig().setPath(path).setPattern(AppConfig.POPattern)).values());
            System.out.println("target : " + path);

            int requestCount = 0;

            ArrayList<PO> skippedItem = new ArrayList<>();
            ArrayList<Thread> workerList = new ArrayList<>();

            GoogleTranslate worker = new GoogleTranslate();
            for (PO oneItem : fileItems) {
                if (oneItem.getSource().equals(oneItem.getTarget())) {
                    oneItem.modifyDoubleQuart();
                    worker.addJob(oneItem);
                    Thread transWork = new Thread(worker);
                    transWork.start();
                    workerList.add(transWork);
                    requestCount++;
                } else {
                    skippedItem.add(oneItem);
                }


                if (requestCount > 0) {
                    System.out.println("wait for Google translate....");
                    for (Thread t : workerList) {
                        try {
                            t.join();
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                    requestCount = 0;
                }

                for (Thread t : workerList) {
                    try {
                        t.join();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }

            //LtransList.addAll(skippedItem);
            LtransList.addAll(worker.getResult());

            String outputName = LtransList.get(1).getFileName() + "_conv.po";
            this.makePOFile(outputName, LtransList);
            LtransList.clear();
        }
    }

    public void filterNewPO() {

        var listFiles = Utils.listFiles(appWorkConfig.getPODirectoryToPath(), "po");
        ArrayList<PO> LtransList = new ArrayList<>();

        for (Path path : listFiles) {
            ArrayList<PO> fileItems = new ArrayList<>(Utils.sourceToMap(new SourceToMapConfig().setPath(path).setPattern(AppConfig.POPattern)).values());
            System.out.println("target : " + path);
            for (PO oneItem : fileItems) {
                if (oneItem.getSource().equals(oneItem.getTarget())) {
                    oneItem.setTarget("");
                    LtransList.add(oneItem);
                }
            }
            System.out.println("target size: " + LtransList.size());

            String outputName = path.getFileName() + "_conv.po";
            this.makePOFile(outputName, LtransList);
            LtransList.clear();
        }
    }

    public void setFuzzyNbyG() {

        var listFiles = Utils.listFiles(appWorkConfig.getPODirectoryToPath(), "po");
        ArrayList<PO> LtransList = new ArrayList<>();

        for (var path : listFiles) {
            ArrayList<PO> fileItems = new ArrayList<>(Utils.sourceToMap(new SourceToMapConfig().setPath(path).setPattern(AppConfig.POPattern)).values());
            System.out.println("target : " + path);
            for (PO oneItem : fileItems) {
                oneItem.setFuzzy(true);
                oneItem.setTarget(GoogleTranslate.ReplaceSpecialChar(oneItem.getTarget())+"-G-");
                LtransList.add(oneItem);

            }
            System.out.println("target size: " + LtransList.size());

            String outputName = path.getFileName() + "_trans.po";
            this.makePOFile(outputName, LtransList);
            LtransList.clear();
        }
    }



    private void makePOFile(String filename ,ArrayList<PO> poList) {

        StringBuilder sb = new StringBuilder();
        System.out.println("po file making... file : "+appWorkConfig.getPODirectoryToPath()+"\\"+filename);
        var file = appWorkConfig.getPODirectoryToPath().resolve(filename);
        for(PO p : poList) {
            if(p.isFuzzy()){
                sb.append("#, fuzzy\n");
            }
            sb.append("msgctxt \"").append(p.getId()).append("\"\nmsgid \"").append(p.getSource()).append("\"\nmsgstr \"").append(p.getTarget()).append("\"\n\n");
        }

        try {
            Files.writeString(file, sb.toString(), AppConfig.CHARSET);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
