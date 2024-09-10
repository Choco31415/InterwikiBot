package InterwikiBot;

import java.awt.event.ActionEvent;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URLEncoder;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Level;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.lang3.text.WordUtils;

import APIcommands.APIcommand;
import APIcommands.AppendText;
import Content.Interwiki;
import Content.Link;
import Content.Page;
import Content.PageLocation;
import Content.Template;
import WikiBot.MediawikiBot;

@SuppressWarnings("unused")
public class InterwikiBot implements Runnable {

	private static final long serialVersionUID = 1L;

	private static MediawikiBot bot;
	
	public BufferPool<String, PLtoCG> downloadBuffer;

	int batchSize = 25;

	private final class PLtoCG {
		private ConnectionGraph cg;
		private PageLocation pageLocation;
	}

	/*
	 * This is where I initialize my custom Mediawiki bot.
	 */
	public InterwikiBot() throws IOException {
		Path familyFile = Paths.get("./src/main/resources/ScratchFamily.txt");
		
		bot = new MediawikiBot(familyFile, "en");

		// Preferences
		bot.queryLimit = batchSize;// The amount of items to get per query call.

		bot.APIdelay = 0.5;// Minimum time between any API commands.
		bot.setWaitBetweenProposedCommands(12);// Minimum time between edits.

		// setLoggerLevel(Level.FINE);// TODO:
	}

	public void main(String[] args) {
		bot.displayGUI("Interwiki Bot", this); // TODO:
	}

	/*
	 * This is where I put my bot code.
	 */
	public void run() {	
		for (String wikiToProcess: bot.getMDM().getWikiPrefixes()) {
			bot.logInfo("Starting scan of all articles for wiki " + wikiToProcess + ".");
			
			String cursor = "" + ((char) 0); // Get pages starting from this point.
			ArrayList<PageLocation> allPages;
			downloadBuffer = new BufferPool<>(batchSize);
			
			do {
				// Get a batch of pages...
				allPages = bot.getAllPages(wikiToProcess, batchSize, cursor);
				if (allPages == null) {
					// Handle error gracefully, hate for program to crash half way through
					bot.logError("Could not download batch. Logging error, moving on.");
					//Level prevLogLevel = logLevel; TODO
					//setLoggerLevel(Level.FINEST);
					allPages = bot.getAllPages(wikiToProcess, batchSize, cursor);
					//setLoggerLevel(prevLogLevel);
				} else {
					// Process batch
					cursor = allPages.get(allPages.size() - 1).getTitle();
		
					instantiateConnectionGraphs(allPages);
					bot.logInfo("Downloaded a new batch.");
		
					// ... and process pages until we don't have enough buffer filled.
					while (downloadBuffer.needsFlushed()) {
						bot.logInfo("Flushing buffer.");
						String language = downloadBuffer.getLargestBufferKey();
						Queue<PLtoCG> buffer = downloadBuffer.flushPool(language);
						processPages(buffer);
					}
				}
				bot.logInfo("Finished processing pages so far.");
			} while (allPages != null && allPages.size() >= batchSize);
	
			// Empty the buffer to finish processing.
			while (!downloadBuffer.isEmpty()) {
				String language = downloadBuffer.getLargestBufferKey();
				Queue<PLtoCG> buffer = downloadBuffer.flushPool(language);
				processPages(buffer);
			}
			
			bot.logInfo("Finished scanning all articles for wiki " + wikiToProcess + ".");
		}
	}

	/**
	 * For each page location in {@code pls}, make a connection graph.
	 * 
	 * @param pls
	 *            A series of page locations to make connection graphs for.
	 */
	public void instantiateConnectionGraphs(ArrayList<PageLocation> pls) {
		try {
			ArrayList<Page> pages = bot.getWikiPages(pls, false);

			for (Page page : pages) {
				// Process each page individually.
				ConnectionGraph cg = new ConnectionGraph();

				// Look at the page's interwikis, and add them to the page's
				// connection graph.
				ArrayList<Interwiki> interwikis = page.getInterwikis();
				ArrayList<PageLocation> linksTo = new ArrayList<>();
				for (Interwiki inter : interwikis) {
					if (inter.getTitle().length() > 0) {
						linksTo.add(inter.getPageLocation());
					}
				}

				cg.addPage(page.getPageLocation(), linksTo);

				// See which pages wee need to investigate, and add them.
				for (PageLocation needed : cg.pagesToDownload()) {
					PLtoCG pc = new PLtoCG();
					pc.cg = cg;
					pc.pageLocation = needed;
					downloadBuffer.addToBuffer(needed.getLanguage(), pc);
				}
			}
		} catch (Exception e) {
			bot.logError("Couldn't instantiate several graphs due to bad server API response.");
		}
	}

	public void processPages(Queue<PLtoCG> toProcess) {
		// Download the pages.
		ArrayList<PageLocation> pls = new ArrayList<>();
		for (PLtoCG c : toProcess) {
			if (!pls.contains(c.pageLocation)) {
				pls.add(c.pageLocation);
			}
		}
		bot.logInfo("Processing pages. Includes: " + pls.get(0));
		ArrayList<Page> pages = bot.getWikiPages(pls, false);
		additionalProcessing(pages);

		for (int i = 0; i < pages.size(); i++) {
			// Now process each page individually.
			Page page = pages.get(i);
			PageLocation originalLoc = page.getPageLocation();

			// If page is redirect, follow it.
			while (page.getRawText().substring(0, 9).equals("#REDIRECT")) {
				Link linkTo = page.getLinks().get(0);
				page = bot.getWikiPage(new PageLocation(page.getLanguage(), linkTo.getDestination()), false);
			}

			for (PLtoCG pc : toProcess) {
				// Check each PLtoCG to see which one generated this page
				// request.
				if (pc.pageLocation.equals(page.getPageLocation()) | toProcess.size() == 1) {
					ConnectionGraph cg = pc.cg; // The connection graph this
												// page is a part of.

					// If there have been redirects, mark them.
					if (!originalLoc.equals(page.getPageLocation())) {
						cg.markAsRedirect(originalLoc, page.getPageLocation());
					}

					// Look at the page's interwikis, and add them to the page's
					// connection graph.
					ArrayList<Interwiki> interwikis = page.getInterwikis();
					ArrayList<PageLocation> linksTo = new ArrayList<>();
					for (Interwiki inter : interwikis) {
						if (inter.getTitle().length() > 0) {
							linksTo.add(inter.getPageLocation());
						}
					}

					cg.addPage(page.getPageLocation(), linksTo);

					// See which pages wee need to investigate, and add them.
					for (PageLocation needed : cg.pagesToDownload()) {
						PLtoCG pc2 = new PLtoCG();
						pc2.cg = cg;
						pc2.pageLocation = needed;
						downloadBuffer.addToBuffer(needed.getLanguage(), pc2);
					}

					// Check if connection graph is completed. If yes, check for
					// any editing needed.
					if (cg.isComplete()) {
						System.out.println(cg);
						processCompletedConnectionGraph(cg);
					}
				}
			}
		}
	}

	public void processCompletedConnectionGraph(ConnectionGraph cg) {
		if (cg.hasOverlappingLanguages()) {
			bot.logInfo("Found conflict!");
			for (PageLocation pl : cg.getKnownPages()) {
				bot.logInfo("" + pl);
			}
		} else {
			// Go to each incomplete page and append the missing interwikis.
			ArrayList<PageLocation> incomplete = cg.getIncompletePages();
			Set<PageLocation> knownPages = cg.getKnownPages();

			for (PageLocation pl : incomplete) {
				String appendText = "";
				ArrayList<PageLocation> linksTo = cg.getPageLinks(pl);

				for (PageLocation knownPage : knownPages) {
					if (!linksTo.contains(knownPage) && knownPage.getLanguage() != pl.getLanguage()) {
						appendText += "[[" + knownPage.getLanguage() + ":" + knownPage.getTitle() + "]]";
					}
				}

				APIcommand command = new AppendText(pl, appendText, "Adding interwikis.");
				bot.proposeEdit(command);
			}

		}
	}

	public void additionalProcessing(ArrayList<Page> pages) {
		for (Page page : pages) {
			if (page.getLanguage().equals("de")) {
				Template en = (Template) page.getPageObject("en", "Template");
				if (en != null && !page.containsInterwiki("en")) {
					if (en.getNumParameters() > 0) {
						page.addInterwiki(new Interwiki("en", en.getParameter(0), -1, -1));
					}
				}
			}
		}
	}
}