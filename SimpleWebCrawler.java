package crawler;

import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.*;
import java.util.regex.*;

public class SimpleWebCrawler implements WebCrawler {
    private Downloader downloader;

    public SimpleWebCrawler(Downloader downloader) throws IOException {
        this.downloader = downloader;
    }

    private static List<String> extractTag(String blank, String tagName) {
        List<String> list = new ArrayList<> ();
        Pattern pattern = Pattern.compile("<" + tagName + "[\\s\\S]*?>");
        Matcher matcher = pattern.matcher(blank);
        while (matcher.find()) {
            list.add(blank.substring(matcher.start(), matcher.end()));
        }
        pattern = Pattern.compile("<" + tagName  + "[\\s\\S]*" + "?</" + tagName + ">");
        matcher = pattern.matcher(blank);
        while (matcher.find()) {
            list.add(blank.substring(matcher.start(), matcher.end()));
        }
        return list;
    }

    private static String deleteComments(String page) {
        return page.replaceAll(  "<!--[\\s\\S]*?-->", "");
    }

    private static String extractAttribute(String tag, String attribute) {
        Pattern pattern = Pattern.compile(attribute + "\\s*=\\s*\"([\\s\\S]*?)\"");
        Matcher matcher = pattern.matcher(tag);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String tagInsides(String tag) {
        Pattern pattern = Pattern.compile(">(.*)<");
        Matcher matcher = pattern.matcher(tag);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String generateLocalFilename(String url) {
        try {
            final String encoded = URLEncoder.encode(url, "UTF-8");
            return encoded.length() < 200 ? encoded : encoded.substring(0, 200);
        } catch (UnsupportedEncodingException e) {
            return url.replaceAll("://", "_").replaceAll("/", "_");
        }
    }

    private static String removeAnchor(String url) {
        for (int i = 0; i < url.length(); i++) {
            if (url.charAt(i) == '#') {
                return url.substring(0, i);
            }
        }
        return url;
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

        Map<String, Page> processed = new HashMap<>();
        List<String> visitHistory = new ArrayList<>();
        Map<String, List<String>> pageChildren = new HashMap<>();

        while (!tasks.isEmpty()) {
            Task currentTask = tasks.pollFirst();

            if (processed.containsKey(currentTask.url)) {
                continue;
            }

            visitHistory.add(currentTask.url);

            if (currentTask.depth == 0) {
                processed.put(currentTask.url, new Page(currentTask.url, ""));
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
                return new Page(currentTask.url, "");
            }

            String title = null;
            for (String tag : extractTag(pageContents, "title")) {
                title = tagInsides(tag);
                if (title != null) {
                    break;
                }
            }
            Page currentPage = new Page(currentTask.url, title);

            pageContents = deleteComments(pageContents);

            List<String> imgList = extractTag(pageContents, "img");
            Set<String> currentPageImages = new HashSet<>();
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
                String completeImgUrl = imgUrl.toString();
                if (!currentPageImages.contains(completeImgUrl)) {
                    String localFilename = generateLocalFilename(completeImgUrl);
                    try {
                        ReadableByteChannel rbc = Channels.newChannel(downloader.download(completeImgUrl));
                        FileOutputStream fos = new FileOutputStream(localFilename);
                        fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                        Image img = new Image(completeImgUrl, localFilename);
                        currentPage.addImage(img);
                        currentPageImages.add(completeImgUrl);
                    } catch (FileNotFoundException e) {
                        System.out.println("Could not create " + localFilename);
                    } catch (IOException e) {
                        System.out.println("Could not download " + completeImgUrl);
                    }
                }
            }

            Set<String> currentPageChildren = new HashSet<>();
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
                String completeChildUrl = childUrl.toString();

                if (!currentPageChildren.contains(completeChildUrl)) {
                    tasks.addLast(new Task(completeChildUrl, currentTask.depth - 1));
                    pageChildren.putIfAbsent(currentTask.url, new ArrayList<>());
                    pageChildren.get(currentTask.url).add(removeAnchor(completeChildUrl));
                    currentPageChildren.add(completeChildUrl);
                }
            }

            processed.put(currentTask.url, currentPage);
        }

        /*for (Map.Entry<String, List<String>> entry : pageChildren.entrySet()) {
            String parent = entry.getKey();
            for (String child : entry.getValue()) {
                processed.get(parent).addLink(processed.get(child));
            }
        }*/

        ListIterator li = visitHistory.listIterator(visitHistory.size());

        while (li.hasPrevious()) {
            String parent = (String) li.previous();
            if (pageChildren.containsKey(parent)) {
                for (String child : pageChildren.get(parent)) {
                    processed.get(parent).addLink(processed.get(child));
                }
            }
        }

        return processed.get(url);
    }
}
