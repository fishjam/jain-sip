/******************************************************************************
 * Product of NIST/ITL Advanced Networking Technologies Division (ANTD)       *
 ******************************************************************************/
package gov.nist.javax.sip.parser;

import gov.nist.core.*;
import gov.nist.javax.sip.message.*;
import gov.nist.javax.sip.header.*;
import java.text.ParseException;
import java.io.*;
//ifdef SIMULATION
/*
import sim.java.net.*;
//endif
*/

/**
 * This implements a pipelined message parser suitable for use
 * with a stream - oriented input such as TCP. The client uses
 * this class by instatiating with an input stream from which
 * input is read and fed to a message parser.
 * It keeps reading from the input stream and process messages in a
 * never ending interpreter loop. The message listener interface gets called
 * for processing messages or for processing errors. The payload specified
 * by the content-length header is read directly from the input stream.
 * This can be accessed from the SIPMessage using the getContent and
 * getContentBytes methods provided by the SIPMessage class. 
 *
 * @version JAIN-SIP-1.1 $Revision: 1.12 $ $Date: 2004-03-18 22:01:19 $
 *
 * @author <A href=mailto:mranga@nist.gov > M. Ranganathan  </A>
 *
 * <a href="{@docRoot}/uncopyright.html">This code is in the public domain.</a>
 *
 * Lamine Brahimi and Yann Duponchel (IBM Zurich) noticed that the parser was
 * blocking so I threw out some cool pipelining which ran fast but only worked
 * when the phase of the full moon matched its mood. Now things are serialized
 * and life goes slower but more reliably.
 *
 * @see  SIPMessageListener
 */
public final class PipelinedMsgParser implements Runnable {

	/**
	 * A filter to read the input
	 */
	class MyFilterInputStream extends FilterInputStream {
		public MyFilterInputStream(InputStream in) {
			super(in);
		}
	}

	/**
	 * The message listener that is registered with this parser.
	 * (The message listener has methods that can process correct
	 * and erroneous messages.)
	 */
	protected SIPMessageListener sipMessageListener;
//ifdef SIMULATION
/*
	private SimThread mythread; // Preprocessor thread
//else
*/
	private Thread mythread; // Preprocessor thread
//endif
//
	private byte[] messageBody;
	private boolean errorFlag;
	private InputStream rawInputStream;
	private int maxMessageSize;
	private int sizeCounter;
	private int messageSize;

	/**
	 * default constructor.
	 */
	protected PipelinedMsgParser() {
		super();

	}

	private static int uid = 0;
	private static synchronized int getNewUid() {
		return uid++;
	}

	/**
	 * Constructor when we are given a message listener and an input stream
	 * (could be a TCP connection or a file)
	 * @param sipMessageListener Message listener which has 
	 * methods that  get called
	 * back from the parser when a parse is complete
	 * @param in Input stream from which to read the input.
	 * @param debug Enable/disable tracing or lexical analyser switch.
	 */
	public PipelinedMsgParser(
		SIPMessageListener sipMessageListener,
		InputStream in,
		boolean debug,
		int maxMessageSize ) {
		this();
		this.sipMessageListener = sipMessageListener;
		rawInputStream = in;
		this.maxMessageSize = maxMessageSize;
//ifndef SIMULATION
//
		mythread = new Thread(this);
		mythread.setName("PipelineThread-" + getNewUid());
//else
/*
		mythread = new SimThread(this);
//endif
*/

	}

	/**
	 * This is the constructor for the pipelined parser.
	 * @param mhandler a SIPMessageListener implementation that
	 *	provides the message handlers to
	 * 	handle correctly and incorrectly parsed messages.
	 * @param in An input stream to read messages from.
	 */

	public PipelinedMsgParser(SIPMessageListener mhandler, 
		InputStream in, int maxMsgSize) {
		this(mhandler, in, false,maxMsgSize);
	}

	/**
	 * This is the constructor for the pipelined parser.
	 * @param in - An input stream to read messages from.
	 */

	public PipelinedMsgParser(InputStream in) {
		this(null, in, false,0);
	}

	/**
	 * Start reading and processing input.
	 */
	public void processInput() {
		mythread.start();
	}

	/**
	 * Create a new pipelined parser from an existing one.
	 * @return A new pipelined parser that reads from the same input
	 * stream.
	 */
	protected Object clone() {
		PipelinedMsgParser p = new PipelinedMsgParser();

		p.rawInputStream = this.rawInputStream;
		p.sipMessageListener = this.sipMessageListener;
//ifdef SIMULATION
/*
		SimThread mythread = new SimThread(p);
//else
*/
		Thread mythread = new Thread(p);
//endif
//
		mythread.setName("PipelineThread");
		return p;
	}

	/**
	 * Add a class that implements a SIPMessageListener interface whose
	 * methods get called * on successful parse and error conditons.
	 * @param mlistener a SIPMessageListener
	 * implementation that can react to correct and incorrect
	 * pars.
	 */

	public void setMessageListener(SIPMessageListener mlistener) {
		sipMessageListener = mlistener;
	}

	/** 
	 * read a line of input (I cannot use buffered reader because we
	 * may need to switch encodings mid-stream!
	 */
	private String readLine(FilterInputStream inputStream) throws IOException {
		StringBuffer retval = new StringBuffer("");
		while (true) {
			try {
				char ch;
				int i = inputStream.read();
				if (i == -1) {
					throw new IOException("End of stream");
				} else 
					ch = (char) i;
				// reduce the available read size by 1 ("size" of a char).
				if ( this.maxMessageSize > 0 ) {
				     this.sizeCounter --;
				     if (this.sizeCounter <= 0) 
					 throw new IOException("Max size exceeded!");
				}
				if (ch != '\r')
					retval.append(ch);
				if (ch == '\n') {
					break;
				}
			} catch (IOException ex) {
				throw ex;
			}
		}
		return retval.toString();
	}

	/**
	 * Read to the next break (CRLFCRLF sequence)
	 */
	private String readToBreak(FilterInputStream inputStream)
		throws IOException {
		StringBuffer retval = new StringBuffer("");
		boolean flag = false;
		while (true) {
			try {
				char ch;
				int i = inputStream.read();
				if (i == -1)
					break;
				else
					ch = (char) i;
				if (ch != '\r')
					retval.append(ch);
				if (ch == '\n') {
					if (flag)
						break;
					else
						flag = true;
				}
			} catch (IOException ex) {
				throw ex;
			}

		}
		return retval.toString();
	}

	/**
	 * This is input reading thread for the pipelined parser.
	 * You feed it input through the input stream (see the constructor)
	 * and it calls back an event listener interface for message 
	 * processing or error.
	 * It cleans up the input - dealing with things like line continuation 
	 */
	public void run() {

		MyFilterInputStream inputStream = null;
		inputStream = new MyFilterInputStream(this.rawInputStream);
		// I cannot use buffered reader here because we may need to switch
		// encodings to read the message body.
		try {
			while (true) {
				this.sizeCounter = this.maxMessageSize;
				this.messageSize = 0;
				StringBuffer inputBuffer = new StringBuffer();

				if (Debug.parserDebug) Debug.println("Starting parse!");

				String line1;
				String line2 = null;

				// ignore blank lines.
				while (true) {
					try {
						line1 = readLine(inputStream);
						if (line1.equals("\n")) {
							if (Debug.parserDebug) 
							    Debug.println("Discarding " + line1);
							continue;
						} else
							break;
					} catch (IOException ex) {
						Debug.printStackTrace(ex);
						return;

					}
				}

				inputBuffer.append(line1);

				while (true) {
					try {
						line2 = readLine(inputStream);
						inputBuffer.append(line2);
						if (line2.trim().equals(""))
							break;
					} catch (IOException ex) {
						Debug.printStackTrace(ex);
						return;

					}
				}
				inputBuffer.append(line2);
				StringMsgParser smp = new StringMsgParser(sipMessageListener);
				smp.readBody = false;
				SIPMessage sipMessage = null;
				try {
					sipMessage = smp.parseSIPMessage(inputBuffer.toString());
					if (sipMessage == null)
						continue;
				} catch (ParseException ex) {
					// Just ignore the parse exception.
					continue;
				}

				if (Debug.parserDebug) Debug.println("Completed parsing message");

				ContentLength cl =
					(ContentLength) sipMessage.getContentLength();
				int contentLength = 0;
				if (cl != null) {
					contentLength = cl.getContentLength();
				} else {
					contentLength = 0;
				}

				if (Debug.parserDebug) {
				   Debug.println("contentLength " + contentLength);
				   Debug.println("sizeCounter " + this.sizeCounter);
				   Debug.println("maxMessageSize " + this.maxMessageSize);
				}


				// TODO Add a timeout to this read so clients 
				// that lie about content length will not hang this
				// thread.

				if (contentLength == 0) {
					sipMessage.removeContent();
				} else if ( maxMessageSize == 0 || 
					contentLength < this.sizeCounter ) {
					byte[] message_body = new byte[contentLength];
					int nread = 0;
					while (nread < contentLength ) {
						try {
							int readlength =
								inputStream.read(
									message_body,
									nread,
									contentLength - nread);
							if (readlength >= 0) {
								nread += readlength;
							} else {
								break;
							}
						} catch (IOException ex) {
							ex.printStackTrace();
							break;
						}
					}
					sipMessage.setMessageContent(message_body);
				} 
				// Content length too large - process the message and
				// return error from there.
				if (sipMessageListener != null) {
					try {
						sipMessageListener.processMessage(sipMessage);
					} catch (Exception ex) {
						// fatal error in processing - close the 
						// connection.
						break;
					}
				}
			}
		} finally {
			try {
				inputStream.close();
			} catch (IOException ioe) {
			}
		}
	}
}
/*
 * $Log: not supported by cvs2svn $
 * Revision 1.11  2004/03/07 22:25:23  mranga
 * Reviewed by:   mranga
 * Added a new configuration parameter that instructs the stack to
 * drop a server connection after server transaction termination
 * set gov.nist.javax.sip.CACHE_SERVER_CONNECTIONS=false for this
 * Default behavior is true.
 *
 * Revision 1.10  2004/02/29 15:32:58  mranga
 * Reviewed by:   mranga
 * bug fixes on limiting the max message size.
 *
 * Revision 1.9  2004/02/29 00:46:34  mranga
 * Reviewed by:   mranga
 * Added new configuration property to limit max message size for TCP transport.
 * The property is gov.nist.javax.sip.MAX_MESSAGE_SIZE
 *
 * Revision 1.8  2004/02/25 21:43:03  mranga
 * Reviewed by:   mranga
 * Added a couple of todo's and removed some debug printlns that could slow
 * code down by a bit.
 *
 * Revision 1.7  2004/02/25 20:52:46  mranga
 * Reviewed by:   mranga
 * Fix TCP transport so messages in excess of 8192 bytes are accepted.
 *
 * Revision 1.6  2004/01/22 18:39:41  mranga
 * Reviewed by:   M. Ranganathan
 * Moved the ifdef SIMULATION and associated tags to the first column so Prep preprocessor can deal with them.
 *
 * Revision 1.5  2004/01/22 14:23:45  mranga
 * Reviewed by:   mranga
 * Fixed some minor formatting issues.
 *
 * Revision 1.4  2004/01/22 13:26:31  sverker
 * Issue number:
 * Obtained from:
 * Submitted by:  sverker
 * Reviewed by:   mranga
 *
 * Major reformat of code to conform with style guide. Resolved compiler and javadoc warnings. Added CVS tags.
 *
 * CVS: ----------------------------------------------------------------------
 * CVS: Issue number:
 * CVS:   If this change addresses one or more issues,
 * CVS:   then enter the issue number(s) here.
 * CVS: Obtained from:
 * CVS:   If this change has been taken from another system,
 * CVS:   then name the system in this line, otherwise delete it.
 * CVS: Submitted by:
 * CVS:   If this code has been contributed to the project by someone else; i.e.,
 * CVS:   they sent us a patch or a set of diffs, then include their name/email
 * CVS:   address here. If this is your work then delete this line.
 * CVS: Reviewed by:
 * CVS:   If we are doing pre-commit code reviews and someone else has
 * CVS:   reviewed your changes, include their name(s) here.
 * CVS:   If you have not had it reviewed then delete this line.
 *
 */
