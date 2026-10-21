--- a/src/main/java/org/purpurmc/purpur/PurpurVersionFetcher.java
+++ b/src/main/java/org/purpurmc/purpur/PurpurVersionFetcher.java
@@ -73,2 +73,8 @@
     private static Component getUpdateStatusMessage() {
+        if (BUILD_INFO.asString(VERSION_SIMPLE).contains("-DEV-")) {
+            final String branch = BUILD_INFO.gitBranch().orElse("unknown branch");
+            final String commit = BUILD_INFO.gitCommit().orElse("unknown commit");
+            return text("Development build " + branch + "@" + commit, NamedTextColor.YELLOW);
+        }
+
         final OptionalInt buildNumber = BUILD_INFO.buildNumber();
