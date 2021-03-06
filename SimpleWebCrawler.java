package crawler;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.*;

public class SimpleWebCrawler implements WebCrawler {
    private Downloader downloader;

    public SimpleWebCrawler(Downloader downloader) throws IOException {
        this.downloader = downloader;
    }

    private static List<String> extractTag(String source, String tag) {
        List<String> list = new ArrayList<>();
        Pattern pattern = Pattern.compile("<" + tag + ".*?>");
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            list.add(source.substring(matcher.start(), matcher.end()));
        }
        return list;
    }

    private static String extractAttribute(String tag, String attribute) {
        Pattern pattern = Pattern.compile(attribute + "\\s*=\\s*\"(.*?)\"");
        Matcher matcher = pattern.matcher(tag);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extractTitle(String source) {
        Pattern pattern = Pattern.compile("<title>" + "(.*)" + "</title>");
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String removeComments(String source) {
        return source.replaceAll("<!--[\\s\\S]*?-->", "");
    }

    private static String replaceEntities(String str) {
        // &amp;nbsp;
        return str.replaceAll("&lt;", "<")
                  .replaceAll("&gt;", ">")
                  .replaceAll("&amp;", "&")
                  .replaceAll("&mdash;", "\u2014")
                  .replaceAll("&nbsp;", "\u00A0");
    }

    private static String removeAnchor(String url) {
        for (int i = 0; i < url.length(); i++) {
            if (url.charAt(i) == '#') {
                return url.substring(0, i);
            }
        }
        return url;
    }

    private static String generateLocalFilename(String url) {
        try {
            final String encoded = URLEncoder.encode(url, "UTF-8");
            return encoded.length() < 200 ? encoded : encoded.substring(0, 200);
        } catch (UnsupportedEncodingException e)
        {
            return url.replaceAll("://", "_").replaceAll("/", "_");
        }
    }

    @Override
    public Page crawl(String url, int depth) {
        class Task {
            private Task(String url, int depth) {
                this.url = url;
                this.depth = depth;
            }
            private String url;
            private int depth;
        }
        Deque<Task> tasks = new ArrayDeque<>();
        tasks.addLast(new Task(url, depth));

        Map<String, Page> processedPages = new HashMap<>();
        Map<String, Image> processedImages = new HashMap<>();
        List<String> visitHistory = new ArrayList<>();
        Map<String, List<String>> pageChildren = new HashMap<>();

        while (!tasks.isEmpty()) {
            Task currentTask = tasks.pollFirst();

            if (processedPages.containsKey(currentTask.url)) {
                continue;
            }

            visitHistory.add(currentTask.url);

            if (currentTask.depth == 0) {
                processedPages.put(currentTask.url, new Page(currentTask.url, ""));
                continue;
            }

            URL currentPageUrl;
            try {
                currentPageUrl = new URL(currentTask.url);
            } catch (MalformedURLException e) {
                System.out.println(currentTask.url + " is not a valid URL");
                continue;
            }

            String pageContents = null;
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(downloader.download(currentTask.url), "utf8"))) {
                StringBuilder text = new StringBuilder();
                String inputLine;
                while ((inputLine = in.readLine()) != null)
                    text.append(inputLine);
                pageContents = text.toString();
            } catch (UnsupportedEncodingException e) {
                System.out.println("UTF-8 is not supported on this machine");
            } catch (IOException e) {
                System.out.println("Page read failed: " + e.getMessage());
            }

            if (pageContents == null) {
                processedPages.put(currentTask.url, new Page(currentTask.url, ""));
                continue;
            }

            String title = replaceEntities(extractTitle(pageContents));
            Page currentPage = new Page(currentTask.url, title);

            pageContents = removeComments(pageContents);

            List<String> imgList = extractTag(pageContents, "img");
            for (String tag : imgList) {
                String imgLink = extractAttribute(tag, "src");
                if (imgLink == null) {
                    continue;
                }
                URL imgUrl;
                try {
                    imgUrl = new URL(currentPageUrl, imgLink);
                } catch (MalformedURLException e) {
                    System.out.println(imgLink + " is not a valid URL");
                    continue;
                }
                String completeImgUrl = replaceEntities(imgUrl.toString());
                if (!processedImages.containsKey(completeImgUrl)) {
                    String localFilename = generateLocalFilename(completeImgUrl);
                    try {
                        Files.copy(downloader.download(completeImgUrl), Paths.get(localFilename), StandardCopyOption.REPLACE_EXISTING);
                        processedImages.put(completeImgUrl, new Image(completeImgUrl, localFilename));
                    } catch (FileNotFoundException e) {
                        System.out.println("Could not create " + localFilename);
                    } catch (IOException e) {
                        System.out.println("Could not download " + completeImgUrl);
                    }
                }
                currentPage.addImage(processedImages.get(completeImgUrl));
            }

            List<String> aList = extractTag(pageContents, "a");
            for (String tag : aList) {
                String childLink = extractAttribute(tag, "href");
                if (childLink == null) {
                    continue;
                }
                URL childUrl;
                try {
                    childUrl = new URL(currentPageUrl, childLink);
                } catch (MalformedURLException e) {
                    System.out.println(childLink + " is not a valid URL");
                    continue;
                }
                String completeChildUrl = replaceEntities(childUrl.toString());

                tasks.addLast(new Task(completeChildUrl, currentTask.depth - 1));
                pageChildren.putIfAbsent(currentTask.url, new ArrayList<>());
                pageChildren.get(currentTask.url).add(removeAnchor(completeChildUrl));
            }

            processedPages.put(currentTask.url, currentPage);
        }

        ListIterator li = visitHistory.listIterator(visitHistory.size());

        while (li.hasPrevious()) {
            String parent = (String) li.previous();
            if (pageChildren.containsKey(parent)) {
                for (String child : pageChildren.get(parent)) {
                    processedPages.get(parent).addLink(processedPages.get(child));
                }
            }
        }

        return processedPages.get(url);
    }
}
