import java.util.regex.Matcher
import java.util.regex.Pattern

// @see http://docs.codehaus.org/display/GMAVEN/Executing+Groovy+Code#ExecutingGroovyCode-Examples

// Get date of given revision
Pattern pattern = Pattern.compile("([0-9]{4})-([0-9]{2})-([0-9]{2})\\s([0-9]{2}):([0-9]{2})\\s[-+]?[0-9]{4}")

def changelogFilePath = "$project.basedir" + (System.getProperty('changelogFilePath') ?: "/src/main/resources/changelog.log")
def ticketsFilePath = "$project.basedir" + "/target/tickets.properties"
def mercurialRevision = "$project.properties.mercurialRevision"
def command = "hg log -r " + mercurialRevision + " --template \"{date|isodate}\""
def INFO = "[INFO] "
def WARN = "[WARN] "

println INFO + "Command: " + command
def proc = command.execute()
proc.waitFor()
def output = proc.text
def exitValue = proc.exitValue()
println INFO + "Output: " + output
println INFO + "Exit code: " + exitValue
if (exitValue) {
   fail("Failed resolving date for release revision.")
}
Matcher matcher = pattern.matcher(output)
def revisionDate
if (matcher.find()) {
   revisionDate = matcher.group()
   println INFO + "Resolved revision date: " + revisionDate
} else {
   fail("Could not parse revision for release from passed arguments. Check output of previous command executed.")
}

// Get date of latestRelease label
println INFO + "Updating changelog file: "  + changelogFilePath

Pattern releasePattern = Pattern.compile("([0-9].*[0-9])")
def file = new File(changelogFilePath)
def firstLine = file.readLines().get(0)
def releaseMatcher = releasePattern.matcher(firstLine)
def latestRelease
if (releaseMatcher.find()) {
   latestRelease = releaseMatcher.group()
   println INFO + "Last release tag found: " + latestRelease
} else {
   fail("Could not parse last release from " + changelogFilePath)
}
def command2 = "hg log -r " + latestRelease + " -b . --template \"{date|isodate}\""

println INFO + "Command: " + command2
proc = command2.execute()
proc.waitFor()
output = proc.text
exitValue = proc.exitValue()
println INFO + "Output: " + output
println INFO + "Exit code: " + exitValue
if (exitValue) {
   fail("Failed taking date for last release.")
}
matcher = pattern.matcher(output)
def latestReleaseDate
if (matcher.find()) {
   latestReleaseDate = matcher.group()
   println INFO + "Resolved last release date: " + latestReleaseDate
}

println INFO + "Comparing new release and last release dates . . ."
if (revisionDate < latestReleaseDate) {
   println WARN + "New release revision must be newer than last release!"
}

// Get commit messages from latestRelease to given revision excluding latestRelease
def command3 = new String[9]

command3[0] = "hg"
command3[1] = "log"
command3[2] = "-r"
command3[3] = "" + mercurialRevision+":0"
command3[4] = "--follow"
command3[5] = "--prune"
command3[6] = "" + latestRelease
command3[7] = "--template"
command3[8] = "'Author': {author|person} 'Desc': {desc|firstline}\\n"
println INFO + "Command: " + command3
def t = command3.execute()
def sout = new StringBuffer()
def serr = new StringBuffer()
t.consumeProcessOutput(sout, serr)
t.waitFor()
if (serr.size() > 0) {
   fail("Failed taking scm change descriptions: " + serr)
}
output = sout.toString()
println INFO + "Changes:\n" + output

// Parse only descriptions starting with EIS
println INFO + "Searching for new changes (skipped jenkins and reviewboard) . . ."
def allSummaries = output.split('\n')
def summaries = []
allSummaries.each {
   def string = it
   if (!(string.contains("eis_acc") || string.contains("ReviewBoard")) && string.size() > 0 && string.contains(" 'Desc': ")) {
	  def summary = string.split(" 'Desc': ")[1]
	  //skip merge, close branch
	  if (!summary.toLowerCase().contains("merge") && !summary.toLowerCase().contains("merging") && !(summary.toLowerCase().contains("clos") && summary.toLowerCase().contains("branch")) && !(summary.toLowerCase().contains("creat") && summary.toLowerCase().contains("branch"))) {
		  summary = summary.replace('\"', '')
		  summaries.add(summary)
		  println INFO + "Adding changeset: " + summary
	  }
   }
}

if (summaries.isEmpty()) {
   fail("No new changes found!")
}

// Append new changes to platform-changelog file
println INFO + "Appending changes to changelog . . ."
def tempName = file.absolutePath.replace(".log", "2.log")
def renameResult = file.renameTo(tempName)
file.delete()
def tempFile = new File(tempName)
if (!renameResult) {
   fail("Failed renaming changelog file.")
}

def newFile = new File(changelogFilePath)

def outStream = newFile.newOutputStream()
def releaseVersion = "$project.properties.releaseVersion"
outStream << "# Platform " + releaseVersion + " release changes\n\n"
for (int i = 0; i < summaries.size(); i++) {
   outStream << summaries[i] + "\n"
}
outStream << "\n"
def input = tempFile.newInputStream()
for (line in input.readLines()) {
   outStream << line
   outStream << "\n"
}
input.close()
outStream.close()

tempFile.delete()

//resolving ticket numbers
println INFO + "Resolving Jira tickets"
def ticketsFile = new File(ticketsFilePath)
def ticketsOutStream = ticketsFile.newOutputStream()
ticketsOutStream << "tickets="
summaries.each {
	ticketsOutStream << "\\n" + it
}
ticketsOutStream.close()
