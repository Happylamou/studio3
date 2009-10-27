package com.aptana.git.core.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.aptana.git.core.GitPlugin;

public class GitRevList
{
	private GitRepository repository;
	private List<GitCommit> commits;

	public static final int NO_LIMIT = -1;

	public GitRevList(GitRepository repo)
	{
		repository = repo;
	}

	// TODO This seems an odd model. Maybe we hide it and hang a method off the repo? Maybe we just return the commits
	// as the return of the walk method calls? Should we take in progress monitors?
	/**
	 * Walks a revision to collect all the commits in reverse chronological order.
	 * 
	 * @param gitRevSpecifier
	 */
	public void walkRevisionListWithSpecifier(GitRevSpecifier gitRevSpecifier)
	{
		walkRevisionListWithSpecifier(gitRevSpecifier, NO_LIMIT);
	}

	/**
	 * Walks a revision to collect commits in reverse chronological order, limited to value of max results.
	 * 
	 * @param rev
	 * @param max
	 *            Maximum number of results to return. {@link #NO_LIMIT} represent no limit.
	 */
	public void walkRevisionListWithSpecifier(GitRevSpecifier rev, int max)
	{
		long start = System.currentTimeMillis();
		List<GitCommit> revisions = new ArrayList<GitCommit>();

		String formatString = "--pretty=format:%H\01%e\01%an\01%s\01%b\01%P\01%at";
		boolean showSign = rev.hasLeftRight();
		if (showSign)
			formatString += "\01%m";

		List<String> arguments = new ArrayList<String>();
		arguments.add("log");
		arguments.add("-z");
		arguments.add("--early-output");
		arguments.add("--topo-order");
		arguments.add("--children");
		if (max > 0)
			arguments.add("-" + max); // only last N revs
		arguments.add(formatString);

		if (rev == null)
			arguments.add("HEAD");
		else
			arguments.addAll(rev.parameters());

		String directory = rev.getWorkingDirectory() != null ? rev.getWorkingDirectory() : repository
				.workingDirectory();

		try
		{
			Process p = GitExecutable.instance().run(directory, arguments.toArray(new String[arguments.size()]));
			InputStream stream = p.getInputStream();

			int num = 0;
			while (true)
			{
				String sha = getline(stream, '\1');
				if (sha == null)
					break;

				// We reached the end of some temporary output. Show what we have
				// until now, and then start again. The sha of the next thing is still
				// in this buffer. So, we use a substring of current input.
				if (sha.charAt(1) == 'i') // Matches 'Final output'
				{
					num = 0;
					setCommits(revisions);
					revisions = new ArrayList<GitCommit>();

					// If the length is < 40, then there are no commits.. quit now
					if (sha.length() < 40)
						break;

					int startIndex = sha.length() - 40;
					sha = sha.substring(startIndex, startIndex + 40);
				}

				String encoding = getline(stream, '\1', "UTF-8");
				GitCommit newCommit = new GitCommit(repository, sha);

				String author = getline(stream, '\1', encoding);
				String subject = getline(stream, '\1', encoding);
				String body = getline(stream, '\1', encoding);
				String parentString = getline(stream, '\1');
				if (parentString != null && parentString.length() != 0)
				{
					if (((parentString.length() + 1) % 41) != 0)
					{
						GitPlugin.logError("invalid parents: " + parentString.length(), null);
						continue;
					}
					int nParents = (parentString.length() + 1) / 41;
					List<String> parents = new ArrayList<String>(nParents);
					for (int parentIndex = 0; parentIndex < nParents; ++parentIndex)
					{
						int stringIndex = parentIndex * 41;
						parents.add(parentString.substring(stringIndex, stringIndex + 40));
					}

					newCommit.setParents(parents);
				}

				long time = readLong(stream); // read 10 chars as a string and parse into a long

				newCommit.setSubject(subject);
				newCommit.setComment(body);
				newCommit.setAuthor(author);
				newCommit.setTimestamp(time);

				if (showSign)
				{
					stream.read(); // Remove separator
					char c = (char) stream.read();
					if (c != '>' && c != '<' && c != '^' && c != '-')
						GitPlugin.logError("Error loading commits: sign not correct", null);
					// newCommit.setSign(c);
				}

				int read = stream.read();
				if (read != 0 && read != -1)
					System.out.println("Error");

				revisions.add(newCommit);

				if (read == -1)
					break;

				if (++num % 1000 == 0)
				{
					setCommits(revisions);
				}

			}

			long duration = System.currentTimeMillis() - start;
			logInfo("Loaded " + num + " commits in " + duration + " ms");
			// Make sure the commits are stored before exiting.
			setCommits(revisions, true);
			p.waitFor();
		}
		catch (Exception e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void logInfo(String string)
	{
		if (GitPlugin.getDefault() != null)
			GitPlugin.logInfo(string);
		else
			System.out.println(string);
	}

	private long readLong(InputStream stream)
	{
		StringBuilder builder = new StringBuilder();
		while (true)
		{
			try
			{
				int read = stream.read();
				if (read == -1)
					break;
				builder.append((char) read);
				if (builder.length() == 10)
					break;
			}
			catch (IOException e)
			{
				break;
			}
		}
		// Since we get time in seconds since epoch, not ms we need to multiply by 1000
		long time = Long.parseLong(builder.toString()) * 1000;
		// HACK for some reason my times are 5 minutes off the console/GitX. Adjust 5 mins
		return time + (5 * 60 * 1000);
	}

	private void setCommits(List<GitCommit> revisions)
	{
		setCommits(revisions, false);
	}

	private void setCommits(List<GitCommit> revisions, boolean trimToSize)
	{
		if (trimToSize)
		{
			if (revisions instanceof ArrayList<?>)
			{
				((ArrayList<?>) revisions).trimToSize();
			}
			this.commits = new ArrayList<GitCommit>(revisions);
			revisions.clear();
			revisions = null;
		}
		else
			this.commits = revisions;
	}

	private String getline(InputStream stream, char c)
	{
		byte[] bytes = read(stream, c);
		if (bytes == null || bytes.length == 0)
			return null;
		return new String(bytes);
	}

	private String getline(InputStream stream, char c, String encoding) throws UnsupportedEncodingException
	{
		if (encoding == null || encoding.length() == 0)
			return getline(stream, c);
		byte[] bytes = read(stream, c);
		return new String(bytes, encoding);
	}

	private byte[] read(InputStream stream, char c)
	{
		List<Byte> list = new ArrayList<Byte>();
		while (true)
		{
			try
			{
				int read = stream.read();
				if (read == -1)
					break;
				char readC = (char) read;
				if (readC == c)
					break;
				list.add((byte) read);
			}
			catch (IOException e)
			{
				break;
			}
		}
		byte[] bytes = new byte[list.size()];
		int i = 0;
		for (Byte by : list)
		{
			bytes[i++] = by.byteValue();
		}
		return bytes;
	}

	public List<GitCommit> getCommits()
	{
		return Collections.unmodifiableList(this.commits);
	}
}
