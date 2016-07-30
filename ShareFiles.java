
// GPL licensed : see http://www.fenyo.net/softs-javashare.html

// (C)_ A. Fenyo - www.fenyo.net - alex@fenyo.net

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.tree.*;
import javax.swing.event.*;
import java.net.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.zip.*;

//import com.ms.security.*; SecurityExceptionEx

import java.applet.*;

//import javax.swing.plaf.TreeUI.*;
//import javax.swing.plaf.basic.*;

class myCellRenderer extends DefaultTreeCellRenderer
{
    public Dimension getPreferredSize()
    {
	return new Dimension(150, 20);
    }
}

class BufferedOutputStreamEx extends BufferedOutputStream
{
    public BufferedOutputStreamEx(OutputStream out)
    {
	super(out);
    }

    public void writeCRLF(String s) throws IOException
    {
	if (ShareFiles.DEBUG == true) {
	    if (s.length() < 30)
		ShareFiles.appendTextArea("POP3: send [" + s + "]\n");
	    else ShareFiles.appendTextArea("POP3: send [...]\n");
	}

	write((s + "\r\n").getBytes());
	flush();
    }
}

class BufferedInputStreamEx extends BufferedInputStream
{
    public BufferedInputStreamEx(InputStream in)
    {
	super(in);
    }

    public String readln() throws IOException
    {
	StringBuffer buf = new StringBuffer();
	int car;

	car = read();
	while (car != '\n' && car != -1) {
	    buf.append((char) car);
	    car = read();
	}

	if (car == -1 && buf.length() == 0) return null;
	return buf.toString();
    }

    public String readCRLF() throws IOException
    {
	StringBuffer buf = new StringBuffer();
	int car;

	car = read();
	while (car != '\n' && car != -1) {
	    buf.append((char) car);
	    car = read();
	}

	if (car == -1 && buf.length() == 0) return null;

	if (buf.length() == 0 || buf.charAt(buf.length() - 1) != '\r') {
	    ShareFiles.appendTextArea("No CR before LF\n");
	    if (ShareFiles.DEBUG == true)
		ShareFiles.appendTextArea("POP3: recvd [" +
					  buf.toString() + "]\n");
	    return buf.toString();
	}

	buf.setLength(buf.length() - 1);
	if (ShareFiles.DEBUG == true)
	    ShareFiles.appendTextArea("POP3: recvd [" +
				      buf.toString() + "]\n");
	return buf.toString();
    }
}

class TCPClient implements Runnable 
{
    private String serverName;
    private String fileName;
    private boolean running = true;
    private Thread thread;
    private Socket socket;
    private BufferedOutputStream output;
    private BufferedInputStreamEx input;
    private int action;
    public volatile static long bytesTransmitted = 0;
    public volatile static long maxBytesTransmitted = 1;
    public volatile static long totalBytesTransmitted = 0;

    private void incTotalBytesTransmitted(int size)
    {
	totalBytesTransmitted += size;
    }

    public TCPClient(String host, String file, int action)
    {
	serverName = host;
	fileName = file;
	this.action = action;
    }

    private void end()
    {
	running = false;
	if (ShareFiles.DEBUG == true)
	    System.out.println("TCPClient: end");
    }

    public void run()
    {
	try {
	    switch (action) {
	    case 0:
		getData();
		break;
	    case 1:
		putData();
		break;
	    default:
		System.out.println("error: unknown action");
		break;
	    }
	}
	catch (IOException e) {
	    ShareFiles.logException(e);
	}
	finally {
	    if (thread != null) {
		ShareFiles.setEnabledMenuItemSend(0, true);
		ShareFiles.setEnabledMenuItemDisconnect(false);
		ShareFiles.setEnabledMenuItemConnect(true);
		ShareFiles.setEnabledMenuItemReceive(true);
	    }
	    bytesTransmitted = 0;
	    maxBytesTransmitted = 1;
	}
    }

    private void putData() throws IOException
    {
	FileInputStream fileInputStream = null;
	GZIPOutputStream gzip = null;
	bytesTransmitted = 0;
	maxBytesTransmitted = 0;

	if (ShareFiles.DEBUG == true)
	    System.out.println("TCPClient.putData()");

	thread = Thread.currentThread();

	ShareFiles.clearTextArea();

	try {
	    socket = new Socket(serverName, ShareFiles.TCPServerPort);
	    output = new BufferedOutputStream(socket.getOutputStream());
	    input = new BufferedInputStreamEx(socket.getInputStream());

	    ShareFiles.setLabelText("Sending local file...");

	    // Send "PUTFILE"
	    // Send filename
	    ShareFiles.appendTextArea("Send \"PUTFILE\" cmd & filename\n");
	    File localFile =
		new File(ShareFiles.config.getLocalDir(), fileName);
	    output.write(("PUTFILE\n" + fileName + "\n").getBytes());
	    output.flush();
	    
	    if (localFile.exists() == false) {
		// There is no such local file
		ShareFiles.setLabelText("File not found");
		return;
	    }

	    maxBytesTransmitted = localFile.length();

	    // Recv remote file length
	    String remoteLengthString = input.readln();
	    ShareFiles.appendTextArea("Recvd remote file length\n");
	    if (ShareFiles.DEBUG == true)
		System.out.println("remotelength=" + remoteLengthString);

	    long remoteLength = Long.parseLong(remoteLengthString);

	    // Compute local CRC
	    fileInputStream = new FileInputStream(localFile);
 	    byte [] buf = new byte[512];
	    CRC32 crc = new CRC32();
 	    int nread;
	    int crcLeft = (int) remoteLength;
	    do {
		nread = fileInputStream.read(buf);
		if (nread > 0) {
		    if (crcLeft < nread) {
			crc.update(buf, 0, crcLeft);
			crcLeft = 0;
		    } else {
			crc.update(buf, 0, nread);
			crcLeft -= nread;
		    }
		}
	    } while (nread != -1 && crcLeft != 0);
	    
	    if (ShareFiles.DEBUG == true)
		System.out.println("crc=" + Long.toString(crc.getValue()));
	    
	    fileInputStream.close();
	    fileInputStream = null;
	    fileInputStream = new FileInputStream(localFile);

	    // Recv CRC
	    String remoteCRCString = input.readln();
	    ShareFiles.appendTextArea("Recvd remote crc\n");
	    if (remoteCRCString == null) {
		if (ShareFiles.DEBUG == true)
		    System.out.println("premature EOF");
		ShareFiles.setLabelText("Premature EOF");
		return;
	    }
	    long remoteCRC = Long.parseLong(remoteCRCString);
	    if (ShareFiles.DEBUG == true)
		System.out.println("remotecrc=" + remoteCRCString);

	    if (remoteLength == localFile.length() &&
		remoteCRC == crc.getValue()) {
		bytesTransmitted = maxBytesTransmitted;
		if (remoteLength == 0)
		    ShareFiles.setLabelText("File transmitted");
		else
		    ShareFiles.setLabelText("Files are identical");
		return;
	    }
	    
	    if (remoteLength < localFile.length() &&
		remoteCRC == crc.getValue()) {
		// Send offset
		ShareFiles.appendTextArea("Send offset\n");
		output.write((new Long(remoteLength).toString() +
			      "\n").getBytes());
		output.flush();
		bytesTransmitted = remoteLength;
		fileInputStream.skip(remoteLength);
	    } else {
		// Send offset
		ShareFiles.appendTextArea("Send null offset\n");
		output.write("0\n".getBytes());
		output.flush();
	    }

	    gzip = new GZIPOutputStream(output);
	    
	    byte [] buf2 = new byte [512];
	    int ret = fileInputStream.read(buf2, 0, buf2.length);
	    while (ret != -1) {
		Thread.yield();
		gzip.write(buf2, 0, ret);
		bytesTransmitted += ret;
		incTotalBytesTransmitted(ret);
		ShareFiles.appendTextArea("Send " + ret + " bytes\n");
		ret = fileInputStream.read(buf2, 0, buf2.length);
	    }
	    ShareFiles.appendTextArea("Send EOF\n");
	    ShareFiles.setLabelText("File transmitted");
	    
	    if (ShareFiles.DEBUG == true)
		System.out.println("file transmitted");
	    return;

	}
	catch (UnknownHostException e) {
	    ShareFiles.setLabelText("Can't resolve server hostname");
	    ShareFiles.logException(e);
	    return;
	}
	catch (IOException e) {
	    ShareFiles.setLabelText("IO error");
	    ShareFiles.logException(e);
	    return;
	}
	finally {
	    end();
	    if (fileInputStream != null) fileInputStream.close();
	    if (gzip != null) gzip.close();
	}
    }

    private void getData() throws IOException
    {
	FileOutputStream fileOutputStream = null;
	GZIPInputStream gzip = null;
	bytesTransmitted = 0;
	maxBytesTransmitted = 0;

	if (ShareFiles.DEBUG == true)
	    System.out.println("TCPClient.getData()");

	thread = Thread.currentThread();

	ShareFiles.clearTextArea();

	try {
	    socket = new Socket(serverName, ShareFiles.TCPServerPort);
	    output = new BufferedOutputStream(socket.getOutputStream());
	    input = new BufferedInputStreamEx(socket.getInputStream());

	    if (fileName.equals(".")) {
		ShareFiles.setLabelText("Getting remote directory...");
	    
		ShareFiles.appendTextArea("Send \"GETDIR\" command\n");
		output.write("GETDIR\n".getBytes());
		output.flush();

		Vector names = new Vector();
		String name;
		while ((name = input.readln()) != null) {
		    ShareFiles.appendTextArea("Recvd file name (" +
					      name.length() + " bytes)\n");
		    incTotalBytesTransmitted(name.length());
		    long len;
		    if (name == null) len = 0;
		    else len = (new Long(input.readln())).longValue();
		    ShareFiles.setFileSize("remote:" + name, len);
		    names.addElement(name);
		}
		ShareFiles.appendTextArea("Recvd EOF\n");

		String [] strings = new String[names.size()];
		names.copyInto(strings);

		maxBytesTransmitted = 1;
		bytesTransmitted = 1;

		ShareFiles.setRemoteFiles(strings);
		ShareFiles.setLabelText("Directory downloaded");

	    } else {

		ShareFiles.setLabelText("Getting remote file...");

		// Send "GETFILE"
		// Send filename
		ShareFiles.appendTextArea("Send \"GETFILE\" cmd & filename\n");
		File localFile =
		    new File(ShareFiles.config.getLocalDir(), fileName);
		output.write(("GETFILE\n" + fileName + "\n").getBytes());

		if (localFile.exists() && localFile.length() > 0) {
		    // There already is a local file

		    // Send local file length (crc size to compute)
		    ShareFiles.appendTextArea("Send local file length\n");
		    output.write((localFile.length() + "\n").getBytes());
		    output.flush();

		    if (ShareFiles.DEBUG == true)
			System.out.println("length=" + Long.toString(localFile.length()));

		    // Compute local CRC
		    FileInputStream fileInputStream = new FileInputStream(localFile);
		    CRC32 crc = new CRC32();
		    try {
			int nread;
			byte [] buf = new byte[512];
			do {
			    nread = fileInputStream.read(buf);
			    if (nread > 0) crc.update(buf, 0, nread);
			} while (nread != -1);

			if (ShareFiles.DEBUG == true)
			    System.out.println("crc=" + Long.toString(crc.getValue()));
		    }
		    finally {
			if (fileInputStream != null)
			    fileInputStream.close();
		    }
		    fileInputStream = null;

		    // Recv remote file length
		    String remoteLengthString = input.readln();
		    ShareFiles.appendTextArea("Recvd remote file length\n");
		    if (remoteLengthString == null) {
			if (ShareFiles.DEBUG == true)
			    System.out.println("premature EOF");
			ShareFiles.setLabelText("Premature EOF");
			return;
		    }
		    incTotalBytesTransmitted(remoteLengthString.length());
		    if (ShareFiles.DEBUG == true)
			System.out.println("remotelength=" + remoteLengthString);
	    
		    long remoteLength = Long.parseLong(remoteLengthString);
		    maxBytesTransmitted = remoteLength;
		    
		    // Recv CRC
		    String remoteCRCString = input.readln();
		    ShareFiles.appendTextArea("Recvd remote crc\n");
		    if (remoteCRCString == null) {
			if (ShareFiles.DEBUG == true)
			    System.out.println("premature EOF");
			ShareFiles.setLabelText("Premature EOF");
			return;
		    }
		    incTotalBytesTransmitted(remoteCRCString.length());
		    long remoteCRC = Long.parseLong(remoteCRCString);
		    if (ShareFiles.DEBUG == true)
			System.out.println("remotecrc=" + remoteCRCString);

		    if (remoteLength == localFile.length() &&
			remoteCRC == crc.getValue()) {
			bytesTransmitted = maxBytesTransmitted;
			ShareFiles.setLabelText("Files are identical");
			return;
		    }

		    if (remoteLength > localFile.length() &&
			remoteCRC == crc.getValue()) {
			// Send offset
			ShareFiles.appendTextArea("Send offset\n");
			output.write((new Long(localFile.length()).toString() +
				      "\n").getBytes());
			output.flush();
			fileOutputStream =
			    new FileOutputStream(localFile.getAbsolutePath(), true);
			bytesTransmitted = localFile.length();
		    } else {
			// Send offset
			ShareFiles.appendTextArea("Send null offset\n");
			output.write("0\n".getBytes());
			output.flush();
			fileOutputStream = new FileOutputStream(localFile);
		    }

		} else {
		    // There is no local file

		    // Send file length
		    // Send offset
		    ShareFiles.appendTextArea("Send null size & null offset\n");
		    output.write("0\n0\n".getBytes());
		    output.flush();

		    // Recv remote file length
		    String remoteLengthString = input.readln();
		    ShareFiles.appendTextArea("Recvd remote file length\n");
		    if (remoteLengthString == null) {
			if (ShareFiles.DEBUG == true)
			    System.out.println("premature EOF");
			ShareFiles.setLabelText("Premature EOF");
			return;
		    }
		    incTotalBytesTransmitted(remoteLengthString.length());
		    if (ShareFiles.DEBUG == true)
			System.out.println("remotelength=" + remoteLengthString);
	    
		    long remoteLength = Long.parseLong(remoteLengthString);
		    maxBytesTransmitted = remoteLength;

		    // Recv remote crc
		    String foo = input.readln();
		    ShareFiles.appendTextArea("Recvd remote crc\n");
		    if (foo != null)
			incTotalBytesTransmitted(foo.length());

		    fileOutputStream = new FileOutputStream(localFile);
		}

		gzip = new GZIPInputStream(input);

		byte [] buf = new byte [512];
		int ret = gzip.read(buf, 0, buf.length);
		if (ret != -1)
		    ShareFiles.appendTextArea("Recvd " + ret + " bytes\n");
		else
		    ShareFiles.appendTextArea("Recvd EOF");
		while (ret != -1) {
		    Thread.yield();
		    fileOutputStream.write(buf, 0, ret);
		    bytesTransmitted += ret;
		    incTotalBytesTransmitted(ret);
		    ret = gzip.read(buf, 0, buf.length);
		    if (ret != -1)
			ShareFiles.appendTextArea("Recvd " + ret + " bytes\n");
		    else
			ShareFiles.appendTextArea("Recvd EOF");
		}

		ShareFiles.setLabelText("File downloaded");
	    }
	}
	catch (UnknownHostException e) {
	    ShareFiles.setLabelText("Can't resolve server hostname");
	    ShareFiles.logException(e);
	    return;
	}
	catch (IOException e) {
	    ShareFiles.setLabelText("IO error");
	    ShareFiles.logException(e);
	    return;
	}
	finally {
	    end();
	    if (fileOutputStream != null) fileOutputStream.close();
	    if (socket != null) socket.close();
	}
    }

    public boolean isRunning()
    {
	return running;
    }

    public void disconnect()
    {
	if (ShareFiles.DEBUG == true)
	    System.out.println("disconnect");

	if (thread != null) {
	    Thread tmpthr = thread;
	    thread = null;
	    tmpthr.interrupt();
	}
    }
}

class TCPServer implements Runnable 
{
    private ServerSocket socket;

    public void run() 
    {
	if (ShareFiles.DEBUG == true)
	    System.out.println("TCPServer.run()");
	
	try {
	    socket = new ServerSocket(ShareFiles.TCPServerPort);
	    while (true) {
		Socket servSocket = socket.accept();
		TCPAccepted tcpAccepted = new TCPAccepted(servSocket);
		new Thread(tcpAccepted).start();
	    }
	}

	catch (IOException e) {
	    ShareFiles.logExceptionAndExit(e);
	}

	
	if (ShareFiles.DEBUG == true)
	    System.out.println("TCPServer: exit");
    }
}

class TCPAccepted implements Runnable 
{
    private Socket socket;
    private BufferedOutputStream output;
    private BufferedInputStreamEx input;

    public TCPAccepted(Socket s) 
    {
	socket = s;
    }
    
    private void close() 
    {
	if (socket != null) {
	    try {
		socket.close();
	    }

	    catch (IOException e) {
		ShareFiles.logException(e);
	    }
	}
    }
    
    private void truncate(String filename)
    {
	if (ShareFiles.DEBUG == true) System.out.println("truncate file");

	File origFile = null;

	try {
	    File file = new File(filename);
	    if (file.length() <= 2048) {
		file.delete();
		return;
	    }

	    origFile = new File("js-" + filename);
	    file.renameTo(origFile);
	    FileInputStream fileInputStream = new FileInputStream(origFile);
	    FileOutputStream fileOutputStream = new FileOutputStream(filename);
	    int nread;
	    long left = origFile.length() - 2048;
	    byte [] buf = new byte [512];
	    
	    do {
		nread = fileInputStream.read(buf);
		if (nread != -1) {
		    if (nread >= left) {
			fileOutputStream.write(buf, 0, (int) left);
			left = 0;
		    } else {
			fileOutputStream.write(buf, 0, nread);
			left -= nread;
		    }
		}
	    } while (nread != -1 && left != 0);
	    fileOutputStream.close();
	    fileInputStream.close();
	}

	catch (FileNotFoundException e) {
	    ShareFiles.logException(e);
	}

	catch (IOException e) {
	    ShareFiles.logException(e);
	}

	finally {
	    if (origFile != null) origFile.delete();
	}
    }

    public void run() 
    {
	if (ShareFiles.DEBUG == true)
	    System.out.println("TCPAccepted.run()");

	try {
	    output = new BufferedOutputStream(socket.getOutputStream());
	    input = new BufferedInputStreamEx(socket.getInputStream());

	    String commandString = input.readln();
	    if (commandString == null) {
		if (ShareFiles.DEBUG == true)
		    System.out.println("premature EOF");
		return;
	    }
	    if (ShareFiles.DEBUG == true)
		System.out.println("command: " + commandString);

	    if (commandString.equals("GETDIR")) {
		String [] files = (new File(".")).list(new FilenameFilter() {
		    public boolean accept(File dir, String name) {
			return new File(dir, name).isFile();
		    }
		});
		if (files == null) return;
		for (int cpt = 0; cpt < files.length; cpt++) {
		    output.write((files[cpt] + "\n").getBytes());
		    output.write((new Long((new File(files[cpt])).length()).toString() + "\n").getBytes());
		}
		output.flush();
		return;
	    }

	    if (commandString.equals("GETFILE")) {
		// Recv file name
		String filenameString = input.readln();
		if (filenameString == null) {
		    if (ShareFiles.DEBUG == true)
			System.out.println("premature EOF");
		    return;
		}
		if (ShareFiles.DEBUG == true)
		    System.out.println("remotefilename: " + filenameString);

		File file = new File(filenameString);
		if (file.canRead() == false) {
		    System.out.println("Can't read file " + filenameString);
		    return;
		}

		// Send file length
		output.write((Long.toString(file.length()) + "\n").getBytes());
		if (ShareFiles.DEBUG == true)
		    System.out.println("length=" + Long.toString(file.length()));
		output.flush();

		// Recv CRC size to compute
		String crcSizeString = input.readln();
		if (crcSizeString == null) {
		    if (ShareFiles.DEBUG == true)
			System.out.println("premature EOF");
		    return;
		}
		long crcSize = Long.parseLong(crcSizeString);
		if (ShareFiles.DEBUG == true)
		    System.out.println("remotecrcsize=" + crcSizeString);

		GZIPOutputStream gzip = null;
		FileInputStream fileInputStream = new FileInputStream(file);
		try {
		    CRC32 crc = new CRC32();
		    byte [] buf = new byte[512];
		    int nread;

		    int crcLeft = (int) crcSize;
		    do {
			nread = fileInputStream.read(buf);
			if (nread > 0) {
			    if (crcLeft < nread) {
				crc.update(buf, 0, crcLeft);
				crcLeft = 0;
			    } else {
				crc.update(buf, 0, nread);
				crcLeft -= nread;
			    }
			}
		    } while (nread != -1 && crcLeft != 0);

		    // Send CRC
		    output.write((Long.toString(crc.getValue()) + "\n").getBytes());
		    output.flush();
		    if (ShareFiles.DEBUG == true)
			System.out.println("crc=" + Long.toString(crc.getValue()));
		    
		    fileInputStream.close();
		    fileInputStream = new FileInputStream(file);

		    // Recv offset
		    String offsetString = input.readln();
		    if (offsetString == null) {
			if (ShareFiles.DEBUG == true)
			    System.out.println("premature EOF");
			return;
		    }

		    long offset = Long.parseLong(offsetString);
		    if (ShareFiles.DEBUG == true)
			System.out.println("offset=" + offset);
		    fileInputStream.skip(offset);

		    gzip = new GZIPOutputStream(output);

		    byte [] buf2 = new byte [512];
		    int ret = fileInputStream.read(buf2, 0, buf2.length);
		    while (ret != -1) {
			gzip.write(buf2, 0, ret);

			ret = fileInputStream.read(buf2, 0, buf2.length);
		    }

		    if (ShareFiles.DEBUG == true)
			System.out.println("file transmitted");
		    return;

		} finally {
		    if (gzip != null) gzip.close();
		    if (fileInputStream != null) fileInputStream.close();
		}
	    }

	    if (commandString.equals("PUTFILE")) {
		// Recv file name
		String filenameString = input.readln();
		if (filenameString == null) {
		    if (ShareFiles.DEBUG == true)
			System.out.println("premature EOF");
		    return;
		}
		if (ShareFiles.DEBUG == true)
		    System.out.println("localfilename: " + filenameString);

		File file = new File(filenameString);
		if (file.exists() == false) {
		    FileOutputStream f = new FileOutputStream(file);
		    f.close();
		}

		// Send file length
		output.write((Long.toString(file.length()) + "\n").getBytes());
		if (ShareFiles.DEBUG == true)
		    System.out.println("length=" + Long.toString(file.length()));

		// Compute CRC
		CRC32 crc = new CRC32();
		byte [] buf = new byte[512];
		int nread;

		FileInputStream fileInputStream = new FileInputStream(file);
		do {
		    nread = fileInputStream.read(buf);
		    if (nread > 0)
			crc.update(buf, 0, nread);
		} while (nread != -1);
		fileInputStream.close();

		// Send CRC
		output.write((Long.toString(crc.getValue()) + "\n").getBytes());
		output.flush();
		if (ShareFiles.DEBUG == true)
		    System.out.println("crc=" + Long.toString(crc.getValue()));
		    
		// Recv offset
		String offsetString = input.readln();
		if (offsetString == null) {
		    if (ShareFiles.DEBUG == true)
			System.out.println("premature EOF");
		    return;
		}

		long offset = Long.parseLong(offsetString);
		if (ShareFiles.DEBUG == true)
		    System.out.println("offset=" + offset);

		GZIPInputStream gzip = null;
		FileOutputStream fileOutputStream = null;
		try {
		    fileOutputStream = 
			new FileOutputStream(filenameString, offset != 0);

		    gzip = new GZIPInputStream(input);

		    byte [] buf2 = new byte [512];
		    int ret = gzip.read(buf2, 0, buf2.length);
		    while (ret != -1) {
			fileOutputStream.write(buf2, 0, ret);
			ret = gzip.read(buf2, 0, buf2.length);
		    }
		    
		    if (ShareFiles.DEBUG == true)
			System.out.println("file transmitted");
		    return;
		}

		catch (java.util.zip.ZipException e) {
		    if (fileOutputStream != null) fileOutputStream.close();
		    fileOutputStream = null;

		    truncate(filenameString);
		}

		finally {
		    if (gzip != null) gzip.close();
		    if (fileOutputStream != null) fileOutputStream.close();
		}
	    }
	}

	catch (EOFException e) {
	    ShareFiles.logException(e);
	}
	catch (IOException e) {
	    ShareFiles.logException(e);
	}
	finally {
	    close();
	    if (ShareFiles.DEBUG == true)
		System.out.println("TCPAccepted: exit");
	}
    }
}

class Mail
{
    private static Vector mails = new Vector();

    private StringBuffer header, body;
    private String uniqueId;
    private boolean deleted = false;

    public static void clean()
    {
	mails = new Vector();
    }

    private static Mail mailAt(int n)
    {
	return (Mail) mails.elementAt(n);
    }

    public static void LIST(BufferedOutputStreamEx output) throws IOException
    {
	int n = 0;
	for (int cpt = 0; cpt < mails.size(); cpt++)
	    if (mailAt(cpt).deleted == false) n++;
	output.writeCRLF("+OK " + n + " message(s)");

	for (int cpt = 0; cpt < mails.size(); cpt++)
	    if (mailAt(cpt).deleted == false)
		output.writeCRLF((cpt + 1) + " " +
				 (mailAt(cpt).header.length() +
				  mailAt(cpt).body.length()));
	output.writeCRLF(".");
    }

    public static void LIST(BufferedOutputStreamEx output, int n) throws IOException
    {
	if (n < 1 || n > mails.size() || mailAt(n - 1).deleted == true) {
	    output.writeCRLF("+ERR LIST");
	    return;
	}
	output.writeCRLF("+OK " + n + " " +
			 (mailAt(n - 1).header.length() + mailAt(n - 1).body.length()));
    }

    public static void UIDL(BufferedOutputStreamEx output) throws IOException
    {
	output.writeCRLF("+OK UIDL");

	for (int cpt = 0; cpt < mails.size(); cpt++)
	    if (mailAt(cpt).deleted == false)
		output.writeCRLF((cpt + 1) + " " + mailAt(cpt).uniqueId);
	output.writeCRLF(".");
    }

    public static void UIDL(BufferedOutputStreamEx output, int n) throws IOException
    {
	if (n < 1 || n > mails.size() || mailAt(n - 1).deleted == true) {
	    output.writeCRLF("+ERR UIDL");
	    return;
	}
	output.writeCRLF("+OK " + n + " " + mailAt(n - 1).uniqueId);
    }

    public static void RETR(BufferedOutputStreamEx output, int n) throws IOException
    {
	if (n < 1 || n > mails.size() || mailAt(n - 1).deleted == true) {
	    output.writeCRLF("+ERR RETR");
	    return;
	}
	output.writeCRLF("+OK " +
			 (mailAt(n - 1).header.length() + mailAt(n - 1).body.length()));
	output.writeCRLF(mailAt(n - 1).header.toString());
	output.writeCRLF("");
	output.writeCRLF(mailAt(n - 1).body.toString());
	output.writeCRLF(".");
    }

    public static String STAT()
    {
	int cnt = 0;
	int size = 0;

	for (int cpt = 0; cpt < mails.size(); cpt++) {
	    if (mailAt(cpt).deleted == false) {
		cnt++;
		size += mailAt(cpt).header.length() + mailAt(cpt).body.length();
	    }
	}

	return Integer.toString(cnt) + " " + Integer.toString(size);
    }

    public static void RSET()
    {
	for (int cpt = 0; cpt < mails.size(); cpt++)
	   mailAt(cpt).deleted = false;
    }

    public static boolean DELE(int n)
    {
	if (n < 1 || n > mails.size() || mailAt(n - 1).deleted == true) return false;
	mailAt(n - 1).deleted = true;
	return true;
    }

    private Mail(StringBuffer header, StringBuffer body)
    {
	this.header = header;
	this.body = body;

	CRC32 crc = new CRC32();
	crc.update((header.toString() + body.toString()).getBytes());
	uniqueId = Long.toString(crc.getValue());
    }

    public static void addMail(StringBuffer header, StringBuffer body)
    {
	/*
	if (ShareFiles.DEBUG == true) {
	    System.out.println("------------------ NEW MAIL:\n" +
			       "-------header:\n" +
			       header.toString() +
			       "-------body:\n" +
			       body.toString());
	}
	*/

	mails.addElement(new Mail(header, body));
    }

    public static void parseMailbox()
    {
	BufferedInputStreamEx input = null;

	Mail.clean();

	try {

	    try {
		input = new BufferedInputStreamEx(new FileInputStream(new
		    File(ShareFiles.config.getLocalDir(), ShareFiles.MBOXNAME)));

		// States : 0=header 1=body
		int state = 1;
		StringBuffer header = new StringBuffer();
		StringBuffer body = new StringBuffer();
		String previousLine = "";
		String line = input.readln();
		if (line != null && line.equals(".")) line = ">.";

		do {
		    if (state == 1 && previousLine.length() == 0 &&
			line.startsWith("From ")) {
			if (header.length() != 0) addMail(header, body);
			header = new StringBuffer(line);
			body = new StringBuffer();
			state = 0;
		    } else if (state == 1) {
			body.append(line + "\r\n");
		    } else if (state == 0 && line.length() == 0) {
			state = 1;
		    } else if (state == 0) {
			header.append(line + "\r\n");
		    }

		    previousLine = line;
		    line = input.readln();
		    if (line != null && line.equals(".")) line = ">.";
		} while (line != null);
		if (header.length() != 0) addMail(header, body);

	    }
	    catch (FileNotFoundException e) {
		ShareFiles.logException(e);
		ShareFiles.setLabelText("Mailbox file not found");
		return;
	    }
	    catch (IOException e) {
		ShareFiles.logException(e);
		ShareFiles.setLabelText("POP3 IO error");
		return;
	    } finally {
		if (input != null) input.close();
	    }

	}
	catch (IOException e) {
	    ShareFiles.setLabelText("IOException when closing mailbox");
	}
    }
}

class POPServer implements Runnable 
{
    private ServerSocket socket;
    private static final int POPServerPort = 110;

    public void run()
    {
	try {
	    acceptConnections();
	}
	catch (IOException e) {
	    ShareFiles.logException(e);
	    ShareFiles.appendTextArea("POP3: acceptConnections() IO error\n");
	}
    }

    private void acceptConnections() throws IOException
    {
	if (ShareFiles.DEBUG == true)
	    System.out.println("POPServer.run()");
	
	try {
	    socket = new ServerSocket(POPServerPort);
	    while (true) {
		Socket servSocket = socket.accept();
		ShareFiles.appendTextArea("POP3: connection accepted\n");

 		Mail.parseMailbox();

		try {
		    BufferedOutputStreamEx output =
			new BufferedOutputStreamEx(servSocket.getOutputStream());
		    BufferedInputStreamEx input =
			new BufferedInputStreamEx(servSocket.getInputStream());

		    output.writeCRLF("+OK JavaShare ready");

		    // USER cmd
		    String cmd;
		    do {
			cmd = input.readCRLF();
			if (cmd == null) return;
			if (cmd.toLowerCase().startsWith("user") == false)
			    output.writeCRLF("-ERR auth");
		    } while (cmd.toLowerCase().startsWith("user") == false);
		    output.writeCRLF("+OK JavaShare ready");

		    // PASS cmd
		    do {
			cmd = input.readCRLF();
			if (cmd == null) return;
			if (cmd.toLowerCase().startsWith("pass") == false)
			    output.writeCRLF("-ERR auth");
		    } while (cmd.toLowerCase().startsWith("pass") == false);
		    output.writeCRLF("+OK JavaShare ready");

		    do {
			cmd = input.readCRLF();
			if (cmd == null) return;

			if (cmd.toLowerCase().startsWith("noop")) {
			    output.writeCRLF("+OK NOOP");
			}

			else if (cmd.toLowerCase().startsWith("rset")) {
			    Mail.RSET();
			}

			else if (cmd.toLowerCase().startsWith("stat")) {
			    output.writeCRLF("+OK " + Mail.STAT());
			}

			else if (cmd.toLowerCase().startsWith("list")) {
			    if (cmd.length() <= 5) Mail.LIST(output);
			    else Mail.LIST(output, Integer.parseInt(cmd.substring(5)));
			}

			else if (cmd.toLowerCase().startsWith("uidl")) {
			    if (cmd.length() <= 5) Mail.UIDL(output);
			    else Mail.UIDL(output, Integer.parseInt(cmd.substring(5)));
			}

			else if (cmd.toLowerCase().startsWith("retr")) {
			    if (cmd.length() <= 5) output.writeCRLF("-ERR RETR");
			    else Mail.RETR(output, Integer.parseInt(cmd.substring(5)));
			}

			else if (cmd.toLowerCase().startsWith("quit")) {
			    output.writeCRLF("+OK bye.");
			}

			else if (cmd.toLowerCase().startsWith("dele ")) {
			    boolean ret = Mail.DELE(Integer.parseInt(cmd.substring(5)));
			    if (ret) output.writeCRLF("+OK DELE");
			    else output.writeCRLF("-ERR DELE");
			}

			else output.writeCRLF("-ERR invalid command");

		    } while (!cmd.toLowerCase().startsWith("quit"));

		}
		catch (IOException e) {
		    ShareFiles.logException(e);
		    ShareFiles.appendTextArea("POP3: service socket IO error\n");
		}
		finally {
		    if (servSocket != null) servSocket.close();
		}
	    }
	}

	catch (BindException e) {
	    ShareFiles.logException(e);
	    ShareFiles.appendTextArea("POP3: can't bind local port\n");
	}

	catch (IOException e) {
	    ShareFiles.logException(e);
	    ShareFiles.appendTextArea("POP3: main socket IO error\n");
	}
	finally {
	    if (socket != null) socket.close();
	}
	
	if (ShareFiles.DEBUG == true)
	    System.out.println("POPServer: exit");
    }
}


class UDPClient implements Runnable 
{
    private String serverName;

    public void run() 
    {
	if (ShareFiles.DEBUG == true)
	    System.out.println("UDPClient.run()");
	
	if (ShareFiles.DEBUG == true)
	    System.out.println("UDPClient: exit");
    }

    public void setServerName()
    {
	// not implemented
    }
}

class UDPServer implements Runnable 
{
    public static final int MAXBUFSIZE = 4096;

    public void run()
    {
	if (ShareFiles.DEBUG == true)
	    System.out.println("UDPServer.run()");
	
	try {
	    DatagramSocket socket = new DatagramSocket(ShareFiles.UDPServerPort);
	    
	    byte [] buf = new byte[MAXBUFSIZE];
	    DatagramPacket p = new DatagramPacket(buf, buf.length);
	    
	    while (true) {
		socket.receive(p);
		System.out.println("datagram from " +
				   p.getAddress().getHostName());
		socket.send(p);
	    }
	}
	
	catch (SocketException e) {
	    ShareFiles.logExceptionAndExit(e);
	}

	catch (IOException e) {
	    ShareFiles.logExceptionAndExit(e);
	}
    
	if (ShareFiles.DEBUG == true)
	    System.out.println("UDPServer: exit");
    }
}

class ConfigDocument extends PlainDocument
{
    private Config config;
    private Method method;
    private String chars;

    public ConfigDocument(Config c, Method m, String ch)
    {
	super();
	config = c;
	method = m;
	chars = ch;
    }

    public void insertString(int offset, String str, AttributeSet a)
	throws BadLocationException
    {
	for (int cpt = 0; cpt < str.length(); cpt++)
	    if (chars.indexOf(str.charAt(cpt)) == -1) return;

	super.insertString(offset, str, a);

	try {
	    method.invoke(config, new Object [] { getText(0, getLength()) });
	}

	catch (InvocationTargetException e) {
	    ShareFiles.logExceptionAndExit(e);
	}

	catch (IllegalAccessException e) {
	    ShareFiles.logExceptionAndExit(e);
	}
    }

    public void remove(int offs, int len) throws BadLocationException
    {
	super.remove(offs, len);

	try {
	    method.invoke(config, new Object [] { getText(0, getLength()) });
	}

	catch (InvocationTargetException e) {
	    ShareFiles.logExceptionAndExit(e);
	}
	
	catch (IllegalAccessException e) {
	    ShareFiles.logExceptionAndExit(e);
	}
    }
}

class Config implements Serializable
{
    private static final String configFile = "ShareFiles.cnf";
    private static final int defaultPacketLength = 512;
    private String remoteHost = "demo.sharefiles.fenyo.net";
    private String localDir = ".";
    private int packetLength = defaultPacketLength;

    public Config()
    {
	super();
    }

    public synchronized void ReadConfig()
    {
	if (ShareFiles.DEBUG == true)
	    System.out.println("ReadConfig()");

	try {
	    FileInputStream istream = new FileInputStream(configFile);
	    ObjectInputStream p = new ObjectInputStream(istream);

	    Config config = (Config) p.readObject();
	    istream.close();

	    remoteHost = config.remoteHost;
	    localDir = config.localDir;
	    packetLength = config.packetLength;
	}

	catch (FileNotFoundException e) {
	    if (ShareFiles.DEBUG == true)
		System.out.println(configFile + ": FileNotFound");
	}

	catch (IOException e) {
	    ShareFiles.logExceptionAndExit(e);
	}

	catch (ClassNotFoundException e) {
	    ShareFiles.logExceptionAndExit(e);
	}

	catch (SecurityException e) {
	    ShareFiles.logException(e);
	}

	catch (Exception e) {
	    if (e.getClass().getName().
		equals("com.ms.security.SecurityExceptionEx"))
		ShareFiles.logException(e);
	    ShareFiles.logExceptionAndExit(e);
	}
    }

    private synchronized void WriteConfig()
    {
	if (ShareFiles.isGuiCreated() == false) return;

	if (ShareFiles.DEBUG == true)
	    System.out.println("WriteConfig()");

	try {
	    FileOutputStream ostream = new FileOutputStream(configFile);
	    ObjectOutputStream p = new ObjectOutputStream(ostream);

	    p.writeObject(this);

	    p.flush();
	    ostream.close();
	}
       
	catch (IOException e) {
	    if (ShareFiles.DEBUG == true)
		System.out.println("WriteConfig() : IOException");

	    ShareFiles.logExceptionAndExit(e);
	}

	catch (SecurityException e) {
	    ShareFiles.logException(e);
	}

	catch (Exception e) {
	    if (e.getClass().getName().
		equals("com.ms.security.SecurityExceptionEx"))
		ShareFiles.logException(e);
	    ShareFiles.logExceptionAndExit(e);
	}
    }

    public synchronized String getRemoteHost()
    {
	return remoteHost;
    }

    public synchronized String getLocalDir()
    {
	return localDir;
    }

    public synchronized int getPacketLength()
    {
	return packetLength;
    }

    public synchronized void setRemoteHost(String s)
    {
	remoteHost = s;
	WriteConfig();
    }

    public synchronized void setLocalDir(String s)
    {
	try {
	    localDir = s;
	    WriteConfig();
	    if (s.equals("")) return;

	    String [] files = (new File(s)).list(new FilenameFilter() {
		public boolean accept(File dir, String name) {
		    return new File(dir, name).isFile();
		}
	    });
	    if (files == null) return;

	    ShareFiles.setLocalFiles(files);
	}
	catch (SecurityException e) {
	    ShareFiles.logException(e);
	}

	catch (Exception e) {
	    if (e.getClass().getName().
		equals("com.ms.security.SecurityExceptionEx"))
		ShareFiles.logException(e);
	    ShareFiles.logExceptionAndExit(e);
	}
    }

    public synchronized void updateLocalDir()
    {
	if (ShareFiles.DEBUG == true)
	    System.out.println("updateLocalDir()");

	String s = getLocalDir();
	setLocalDir(s);
    }

    public synchronized void setPacketLength(String s)
    {
	try {
	    packetLength = (new Integer(s)).intValue();
	}
	catch (NumberFormatException e) {
	    packetLength = defaultPacketLength;
	}
	WriteConfig();
    }
}

public class ShareFiles
{
    public static final int UDPServerPort = 7576;
    public static final int TCPServerPort = 7575;
    public static final boolean DEBUG = false;
    public static final String MBOXNAME = "mailbox";

    private static boolean guiCreated = false;

    public static final Config config = new Config();

    private static JProgressBar progressBar;
    private static JProgressBar speedBar;

    private static TCPClient tcpClient;
    private static TCPServer tcpServer;
    private static UDPClient udpClient;
    private static UDPServer udpServer;

    private static JDialog dialogHelp;
    private static JPanel panelHelp;

    private static JDialog dialogAuthor;
    private static JPanel panelAuthor;

    private static JDialog dialogAbout;
    private static JPanel panelAbout;

    private static JDialog dialogOptions;
    private static JPanel panelOptions;

    private static JFrame frame;

    private static DefaultMutableTreeNode localNode =
	new DefaultMutableTreeNode("Local Files");
    private static DefaultMutableTreeNode remoteNode
	= new DefaultMutableTreeNode("Remote Files");

    private static JTree tree;
    private static JLabel label;

    private static JMenuItem menuItemConnect;
    private static JMenuItem menuItemDisconnect;
    private static JMenuItem menuItemReceive;
    private static JMenuItem menuItemSend;

    private static JTextArea textArea;

    private static javax.swing.Timer timer;

    private static Image imageAlex = null;
    private static Image imageAbout = null;

    public ShareFiles()
    {
	super();
    }

    public ShareFiles(Image alex, Image about)
    {
	super();
	imageAlex = alex;
	imageAbout = about;
    }

    private static abstract class MyTreeSelectionListener
	implements TreeSelectionListener
    {
 	abstract public void valueChanged(TreeSelectionEvent e);
    }

    private static abstract class MyTreeWillExpandListener
	implements TreeWillExpandListener
    {
 	abstract public void treeWillCollapse(TreeExpansionEvent e);
 	abstract public void treeWillExpand(TreeExpansionEvent e);
    }

    private static Hashtable fileSize = new Hashtable();

    public static void setFileSize(String file, long size)
    {
	synchronized(fileSize) {
	    fileSize.put(file, new Long(size));
	}
    }

    private static long getFileSize(String file)
    {
	synchronized(fileSize) {
	    return ((Long) fileSize.get(file)).longValue();
	}
    }

    public static boolean isGuiCreated()
    {
	return guiCreated;
    }

    public static void logException(Exception e) 
    {
	System.out.println("exception: " +
			   e.getClass().toString() + " [" +
			   e.getMessage() + "]");
    }

    public static void logExceptionAndExit(Exception e) 
    {
	logException(e);
	System.exit(1);
    }

    public static void setLocalFiles(final String [] s)
    {
	if (isGuiCreated() == false || SwingUtilities.isEventDispatchThread() == true) {
	    // synchronized(localNode) {
		localNode.removeAllChildren();
		for (int cpt = 0; cpt < s.length; cpt++) {
		    localNode.add(new DefaultMutableTreeNode(s[cpt]));
		    setFileSize("local:" + s[cpt],
				(new File(config.getLocalDir(), s[cpt])).length());
		}
		if (tree != null && tree.getModel() != null)
		    ((DefaultTreeModel) tree.getModel()).reload();
		ShareFiles.setEnabledMenuItemSend(1, false);
		//}
	} else {
	    SwingUtilities.invokeLater(new Runnable() {
		public void run() {
		    //		    synchronized(localNode) {
			localNode.removeAllChildren();
			for (int cpt = 0; cpt < s.length; cpt++) {
			    localNode.add(new DefaultMutableTreeNode(s[cpt]));
			    setFileSize("local:" + s[cpt],
					(new File(config.getLocalDir(), s[cpt])).length());
			}
			if (tree != null && tree.getModel() != null)
			    ((DefaultTreeModel) tree.getModel()).reload();
			ShareFiles.setEnabledMenuItemSend(1, false);
			//}
		}
	    });
	}
    }

    public static void setRemoteFiles(final String [] s)
    {
	if (isGuiCreated() == false || SwingUtilities.isEventDispatchThread() == true) {
	    //	    synchronized(remoteNode) {
		remoteNode.removeAllChildren();
		for (int cpt = 0; cpt < s.length; cpt++) {
		    remoteNode.add(new DefaultMutableTreeNode(s[cpt]));
		}
		if (tree != null && tree.getModel() != null)
		    ((DefaultTreeModel) tree.getModel()).reload();
		//}
	    ShareFiles.setEnabledMenuItemSend(1, false);
	} else {
	    SwingUtilities.invokeLater(new Runnable() {
		public void run() {
		    //		    synchronized(remoteNode) {
			remoteNode.removeAllChildren();
			for (int cpt = 0; cpt < s.length; cpt++) {
			    remoteNode.add(new DefaultMutableTreeNode(s[cpt]));
			}
			if (tree != null && tree.getModel() != null)
			    ((DefaultTreeModel) tree.getModel()).reload();
			//}
		    ShareFiles.setEnabledMenuItemSend(1, false);
		}
	    });
	}
    }

    private static Dimension getTextDimension(JComponent component,
					      String string)
    {
	FontMetrics metrics = component.getFontMetrics(component.getFont());

	return new Dimension(metrics.stringWidth(string),
			     metrics.getMaxDescent() + metrics.getMaxAscent());
    }

    private static void updatePreferredSize(JComponent component,
					    String string)
    {
	Dimension dimension = getTextDimension(component, string);
	component.setPreferredSize(dimension);
    }

    private static void updatePreferredSize(JComponent component,
					    String string,
					    Dimension dim)
    {
	Dimension dimension = getTextDimension(component, string);
	Dimension newDim = new Dimension(dimension.width + dim.width,
					 dimension.height + dim.height);
	component.setPreferredSize(newDim);
    }

    public static void setLabelText(final String s)
    {
// 	synchronized(label) {
// 	}
	if (isGuiCreated() == false || SwingUtilities.isEventDispatchThread() == true) {
 	    label.setText(s);
	} else {
	    SwingUtilities.invokeLater(new Runnable() {
		public void run() {
		    label.setText(s);
		}
	    });
	}
    }

    public static void setEnabledMenuItemConnect(final boolean b)
    {
	if (isGuiCreated() == false || SwingUtilities.isEventDispatchThread() == true) {
	    menuItemConnect.setEnabled(b);
	} else {
	    SwingUtilities.invokeLater(new Runnable() {
		public void run() {
		    menuItemConnect.setEnabled(b);
		}
	    });
	}
    }

    public static void setEnabledMenuItemDisconnect(final boolean b)
    {
	if (isGuiCreated() == false || SwingUtilities.isEventDispatchThread() == true) {
	    menuItemDisconnect.setEnabled(b);
	} else {
	    SwingUtilities.invokeLater(new Runnable() {
		public void run() {
		    menuItemDisconnect.setEnabled(b);
		}
	    });
	}
    }

    public static void setEnabledMenuItemReceive(final boolean b)
    {
	if (isGuiCreated() == false || SwingUtilities.isEventDispatchThread() == true) {
	    menuItemReceive.setEnabled(b);
	} else {
	    SwingUtilities.invokeLater(new Runnable() {
		public void run() {
		    menuItemReceive.setEnabled(b);
		}
	    });
	}
    }

    // src0 : connection state (true = not connected)
    static boolean src0 = true;
    // src1 : selection state (true = selected)
    static boolean src1 = false;
    public static void setEnabledMenuItemSend(int src, boolean b)
    {
	if (src == 0) src0 = b;
	if (src == 1) src1 = b;

	if (isGuiCreated() == false) return;

	if (SwingUtilities.isEventDispatchThread() == true) {
	    menuItemSend.setEnabled(src0 && src1);
	} else {
	    SwingUtilities.invokeLater(new Runnable() {
		public void run() {
		    menuItemSend.setEnabled(src0 && src1);
		}
	    });
	}
    }

    private static void transferFile(String file, int action)
    {
	if (DEBUG == true && tcpClient != null)
	    System.out.println("tcpClient already started");

	if (tcpClient != null && tcpClient.isRunning() == true) {
	    setLabelText("Error: transfer in progress");
	    return;
	}

	setEnabledMenuItemSend(0, false);
	setEnabledMenuItemDisconnect(true);
	setEnabledMenuItemConnect(false);
	setEnabledMenuItemReceive(false);

	tcpClient = new TCPClient(config.getRemoteHost(), file, action);
	new Thread(tcpClient).start();
	if (DEBUG == true) System.out.println("new tcpClient started");
    }

    private static void getFile(String file)
    {
	transferFile(file, 0);
    }

    private static void putFile(String file)
    {
	transferFile(file, 1);
    }

    private static void disconnect()
    {
	if (tcpClient != null) tcpClient.disconnect();

	tcpClient = null;
	ShareFiles.setEnabledMenuItemSend(0, true);
	ShareFiles.setEnabledMenuItemDisconnect(false);
	ShareFiles.setEnabledMenuItemConnect(true);
	ShareFiles.setEnabledMenuItemReceive(true);

	ShareFiles.setLabelText("Disconnected");
    }

    private static Date lastDate = new Date();
    private static long lastTotal = 0;
    private static Vector history = new Vector();

    private static void updateSliders()
    {
	//	System.out.println("update sliders");

	Date now = new Date();
	long bytes, maxBytes, totalBytes;
	if (tcpClient == null) {
	    bytes = 0;
	    maxBytes = 1;
	    totalBytes = 0;
	} else {
	    bytes = tcpClient.bytesTransmitted;
	    maxBytes = tcpClient.maxBytesTransmitted;
	    totalBytes = tcpClient.totalBytesTransmitted;
	}
	if(bytes > maxBytes)  {
	    System.out.println("error: too many bytes");
	    return;
	}

	if (maxBytes != 0) {
	    progressBar.setValue((int) ((100 * bytes) / maxBytes));
	    progressBar.setString("Transfer : " +
				  (int) ((100 * bytes) / maxBytes) + "%");
	    /*
	    if (DEBUG == true)
		System.out.println("progressBar 2 : " +
				   (int) ((100 * bytes) / maxBytes));
	    */
	} else {
	    progressBar.setValue(0);
	    progressBar.setString("Transfer : 0%");
	}

	history.addElement(new Long(now.getTime()));
	history.addElement(new Long(totalBytes));
	// average on the last 10 seconds (20/2 * 1000 /1000)
	if (history.size() > 20) {
	    history.removeElementAt(0);
	    history.removeElementAt(0);
	}
	long ms1 = ((Long) history.elementAt(0)).longValue();
	long cnt1 = ((Long) history.elementAt(1)).longValue();
	long ms2 = now.getTime();
	long cnt2 = totalBytes;

	if (ms1 != ms2) {
	    int throughput = (int) ((8000.0 * (cnt2 - cnt1)) / (ms2 - ms1));
	    if (throughput < 0) {
		if (DEBUG == true)
		    System.out.println("out of arithmetical capacity " +
				       ms1 + "/" + cnt1 + " " + ms2 + "/" + cnt2);
		return;
	    }

	    speedBar.setValue(throughput);
	    speedBar.setString(throughput + " bit/s");
	    /*
	    if (DEBUG == true)
		System.out.println("progressBar 1 : " + throughput);
	    */
	} else {
	    speedBar.setValue(0);
	    speedBar.setString("0 bit/s");
	}
    }

    public static void clearTextArea()
    {
	if (isGuiCreated() == false || SwingUtilities.isEventDispatchThread() == true) {
	    textArea.setText("JavaShare © A.Fenyo 2001\n");
	} else {
	    SwingUtilities.invokeLater(new Runnable() {
		public void run() {
		    textArea.setText("JavaShare © A.Fenyo 2001\n");
		}
	    });
	}
    }
    
    public static void appendTextArea(final String s)
    {
	if (isGuiCreated() == false || SwingUtilities.isEventDispatchThread() == true) {
	    textArea.append(s);
	    textArea.setCaretPosition(textArea.getText().length());
	} else {
	    SwingUtilities.invokeLater(new Runnable() {
		public void run() {
		    textArea.append(s);
		    textArea.setCaretPosition(textArea.getText().length());
		}
	    });
	}
    }

    public static void main(String [] args)
    {
	try {

	    if (DEBUG == true)
		System.out.println("main()");

	    if (args.length >= 1) {
		if (args[0].equals("test")) {

		    System.exit(0);
		}

		if (args[0].equals("crc")) {
		    if (args.length != 3) {
			System.out.println("crc: invalid number of arguments");
			System.exit(1);
		    }

		    try {
			FileInputStream fileInputStream = new FileInputStream(args[1]);
			byte [] buf = new byte[512];
			CRC32 crc = new CRC32();
			int nread;
			int crcLeft = (int) Long.parseLong(args[2]);
			do {
			    nread = fileInputStream.read(buf);
			    if (nread > 0) {
				if (crcLeft < nread) {
				    crc.update(buf, 0, crcLeft);
				    crcLeft = 0;
				} else {
				    crc.update(buf, 0, nread);
				    crcLeft -= nread;
				}
			    }
			} while (nread != -1 && crcLeft != 0);
			System.out.println("crc=" + Long.toString(crc.getValue()));
		    }
		    catch (FileNotFoundException e) {
			ShareFiles.logExceptionAndExit(e);
		    }
		    catch (IOException e) {
			ShareFiles.logExceptionAndExit(e);
		    }
		    System.exit(0);
		}
		
		if (args[0].equals("server")) {
		    if (args.length > 1)
			System.out.println("server: invalid number of arguments");

		    udpServer = new UDPServer();
		    new Thread(udpServer).start();
		    
		    tcpServer = new TCPServer();
		    tcpServer.run();
		    System.exit(0);
		} else {
		    System.out.println("invalid arguments");
		    System.exit(1);
		}
	    }
	    
	    if (args.length > 1) {
		System.out.println("invalid arguments");
		System.exit(1);
	    }
	    
	    // Read config file
	    config.ReadConfig();
	    
	    // Frame
	    frame = new JFrame("JavaShare");
	    
	    // Dialog HELP
	    dialogHelp = new JDialog(frame, "Help", true);
	    panelHelp = new JPanel();
	    dialogHelp.getContentPane().add(panelHelp);
	    
	    JTextArea dialogHelpTextArea =
		new JTextArea("JavaShare © A. Fenyo 2001\n  alex@fenyo.net\n  http://www.fenyo.net/JavaShare.html\n\nJavaShare has been designed by A. Fenyo\n\nRelease 1.0-alpha");
	    dialogHelpTextArea.setEditable(false);
	    dialogHelpTextArea.setLineWrap(true);
	    dialogHelpTextArea.setWrapStyleWord(true);
	    dialogHelpTextArea.setPreferredSize(new Dimension(200, 200));
	    
	    JScrollPane dialogHelpAreaScrollPane = new JScrollPane(dialogHelpTextArea);
	    dialogHelpAreaScrollPane.
		setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
	    dialogHelpAreaScrollPane.setAlignmentX(JPanel.CENTER_ALIGNMENT);
	    panelHelp.add(dialogHelpAreaScrollPane);
	    
	    dialogHelp.pack();
	    
	    // Dialog AUTHOR
	    dialogAuthor = new JDialog(frame, "About the Author", true);
	    panelAuthor = new JPanel();
	    dialogAuthor.getContentPane().add(panelAuthor);
	    
	    JLabel labelAuthor = null;
	    try {
		if (imageAlex == null)
		    labelAuthor = new JLabel(new ImageIcon("alex.gif"));
		else
		    labelAuthor = new JLabel(new ImageIcon(imageAlex));
	    }

	    catch (SecurityException e) {
		ShareFiles.logException(e);
	    }

	    catch (Exception e) {
		if (e.getClass().getName().
		    equals("com.ms.security.SecurityExceptionEx"))
		    ShareFiles.logException(e);
		ShareFiles.logExceptionAndExit(e);
	    }

	    if (labelAuthor != null) panelAuthor.add(labelAuthor);
	    dialogAuthor.pack();
	    
	    // Dialog ABOUT
	    dialogAbout = new JDialog(frame, "About JavaShare", true);
	    panelAbout = new JPanel();
	    dialogAbout.getContentPane().add(panelAbout);
	    
	    JLabel labelAbout = null;
	    try {
		if (imageAbout == null)
		    labelAbout = new JLabel(new ImageIcon("about.gif"));
		else
		    labelAbout = new JLabel(new ImageIcon(imageAbout));
	    }

	    catch (SecurityException e) {
		ShareFiles.logException(e);
	    }

	    catch (Exception e) {
		if (e.getClass().getName().
		    equals("com.ms.security.SecurityExceptionEx"))
		    ShareFiles.logException(e);
		ShareFiles.logExceptionAndExit(e);
	    }

	    if (labelAbout != null) panelAbout.add(labelAbout);
	    dialogAbout.pack();
	    
	    // Dialog OPTIONS
	    dialogOptions = new JDialog(frame, "Options", true);
	    panelOptions = new JPanel();
	    dialogOptions.getContentPane().add(panelOptions);

	    panelOptions.setPreferredSize(new Dimension(200,125));
	    
	    panelOptions.setLayout(new BoxLayout(panelOptions, BoxLayout.Y_AXIS));
	    panelOptions.setAlignmentX(JPanel.LEFT_ALIGNMENT);
	    
	    JTextField hostTextField =
		new JTextField(new
			       ConfigDocument(config,
					      Config.class.getMethod("setRemoteHost",
								     new Class [] { String.class } ),
					      "abcdefghijklmnopqrstuvwxyz1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ-."),
			       config.getRemoteHost(), 15);
	    panelOptions.add(hostTextField);
	    hostTextField.
		setBorder(BorderFactory.
			  createCompoundBorder(BorderFactory.
					       createTitledBorder("remote host"),
					       BorderFactory.
					       createEmptyBorder(1,1,1,1)));

	    JTextField dirTextField =
		new JTextField(new
			       ConfigDocument(config,
					      Config.class.getMethod("setLocalDir",
								     new Class [] { String.class } ),
					      "abcdefghijklmnopqrstuvwxyz1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ -._/\\ÈË‡:"),
			       config.getLocalDir(), 15);
	    panelOptions.add(dirTextField);
	    dirTextField.
		setBorder(BorderFactory.
			  createCompoundBorder(BorderFactory.
					       createTitledBorder("local directory"),
					       BorderFactory.
					       createEmptyBorder(1,1,1,1)));
	    
	    JTextField lengthTextField =
		new JTextField(new
			       ConfigDocument(config,
					      Config.class.getMethod("setPacketLength",
								     new Class [] { String.class } ),
					      "1234567890"),
			       new Integer(config.getPacketLength()).toString(), 15);
	    panelOptions.add(lengthTextField);
	    lengthTextField.
		setBorder(BorderFactory.
			  createCompoundBorder(BorderFactory.
					       createTitledBorder("packet length"),
					       BorderFactory.
					       createEmptyBorder(1,1,1,1)));
	    
	    dialogOptions.pack();
	    
	    // Panel
	    JPanel panel = new JPanel();
	    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
	    
	    //	panel.add(new JButton());
	    //	panel.add(new JButton());
	    
	    frame.getContentPane().add(panel);
	    frame.addWindowListener(new WindowAdapter() {
		public void windowClosing(WindowEvent e) 
		    {
			System.exit(0);
		    }
	    });
	    
	    // Dialog ABOUT JAVA SHARE
	    
	    // Menu bar
	    JMenuBar menuBar = new JMenuBar();
	    frame.setJMenuBar(menuBar);
	    
	    // File menu
	    JMenu menuFile = new JMenu("File");
	    menuFile.setMnemonic(KeyEvent.VK_F);
	    updatePreferredSize(menuFile,
				menuFile.getText(),
				new Dimension(10,0));
	    
	    JMenuItem menuItemOptions = new JMenuItem("Options");
	    menuItemOptions.setMnemonic(KeyEvent.VK_O);
	    menuFile.add(menuItemOptions);
	    menuItemOptions.addActionListener(new ActionListener() {
		public void actionPerformed(ActionEvent e) 
		    {
			dialogOptions.show();
		    }
	    });
	    updatePreferredSize(menuItemOptions,
				menuItemOptions.getText(),
				new Dimension(30,5));
	    
	    JMenuItem menuItemRegister = new JMenuItem("Register");
	    menuItemRegister.setMnemonic(KeyEvent.VK_R);
	    menuFile.add(menuItemRegister);
	    updatePreferredSize(menuItemRegister,
				menuItemRegister.getText(),
				new Dimension(30,5));
	    menuItemRegister.addActionListener(new ActionListener() {
		public void actionPerformed(ActionEvent e) 
		    {
			JOptionPane.
			    showMessageDialog(frame,
					      "Register on\nwww.fenyo.net/JavaShare.html",
					      "Register",
					      JOptionPane.WARNING_MESSAGE);
		    }
	    });
	    
	    
	    menuFile.addSeparator();
	    
	    JMenuItem menuItemExit = new JMenuItem("Exit");
	    menuItemExit.setMnemonic(KeyEvent.VK_X);
	    menuFile.add(menuItemExit);
	    menuItemExit.addActionListener(new ActionListener() {
		public void actionPerformed(ActionEvent e) 
		    {
			System.exit(0);
		    }
	    });
	    updatePreferredSize(menuItemExit,
				menuItemExit.getText(),
				new Dimension(30,5));
	    
	    // Transfer menu
	    JMenu menuTransfer = new JMenu("Transfer");
	    menuTransfer.setMnemonic(KeyEvent.VK_T);
	    updatePreferredSize(menuTransfer,
				menuTransfer.getText(),
				new Dimension(10,0));
	    
	    menuItemSend = new JMenuItem("Transfer");
	    menuItemSend.setMnemonic(KeyEvent.VK_T);
	    menuTransfer.add(menuItemSend);
	    menuItemSend.addActionListener(new ActionListener() {
		public void actionPerformed(ActionEvent e) 
		    {
			if (tree.getSelectionCount() == 0) return;
			TreePath path = tree.getSelectionPath();

			if (path.getPathCount() < 2) return;
			DefaultMutableTreeNode topLevelNode =
			    (DefaultMutableTreeNode) path.getPathComponent(1);
			DefaultMutableTreeNode node = (DefaultMutableTreeNode)
			    path.getPathComponent(path.getPathCount() - 1);

			if (topLevelNode == localNode) {
			    if (DEBUG == true) System.out.println("Local Node");
			    if (path.getPathCount() > 2)
				putFile((String) node.getUserObject());
			}

			if (topLevelNode == remoteNode) {
			    if (DEBUG == true) System.out.println("Remote Node");
			    if (path.getPathCount() > 2)
				getFile((String) node.getUserObject());
			    else
				getFile(".");
			}
			if (DEBUG == true)
			    System.out.println((String) node.getUserObject());
		    }
	    });
	    updatePreferredSize(menuItemSend,
				menuItemSend.getText(),
				new Dimension(30,5));
	    
	    menuItemReceive = new JMenuItem("Remote Directory");
	    menuItemReceive.setMnemonic(KeyEvent.VK_R);
	    menuTransfer.add(menuItemReceive);
	    menuItemReceive.addActionListener(new ActionListener() {
		public void actionPerformed(ActionEvent e) 
 		    {
	 		getFile(".");
		    }
	    });
	    updatePreferredSize(menuItemReceive,
				menuItemReceive.getText(),
				new Dimension(30,5));
	    
	    menuItemSend.setEnabled(false);

	    menuItemConnect = new JMenuItem("Check mail");
	    menuItemConnect.setMnemonic(KeyEvent.VK_C);
	    menuTransfer.add(menuItemConnect);
	    updatePreferredSize(menuItemConnect,
				menuItemConnect.getText(),
				new Dimension(30,5));
	    menuItemConnect.addActionListener(new ActionListener() {
		public void actionPerformed(ActionEvent e) 
		    {
			getFile(MBOXNAME);
		    }
	    });
	    
	    menuTransfer.addSeparator();
	    
	    menuItemDisconnect = new JMenuItem("Disconnect");
	    menuItemDisconnect.setMnemonic(KeyEvent.VK_D);
	    menuTransfer.add(menuItemDisconnect);
	    updatePreferredSize(menuItemDisconnect,
				menuItemDisconnect.getText(),
				new Dimension(30,5));
	    menuItemDisconnect.addActionListener(new ActionListener() {
		public void actionPerformed(ActionEvent e) 
		    {
			disconnect();
		    }
	    });

	    setEnabledMenuItemConnect(true);
	    setEnabledMenuItemDisconnect(false);
	    setEnabledMenuItemReceive(true);
	    
	    // Help menu
	    JMenu menuHelp = new JMenu("Help");
	    menuHelp.setMnemonic(KeyEvent.VK_H);
	    updatePreferredSize(menuHelp,
				menuHelp.getText(),
				new Dimension(10,0));
	    
	    JMenuItem menuItemHelp = new JMenuItem("Help on JavaShare");
	    menuItemHelp.setMnemonic(KeyEvent.VK_H);
	    menuHelp.add(menuItemHelp);
	    menuItemHelp.addActionListener(new ActionListener() {
		public void actionPerformed(ActionEvent e) 
		    {
			dialogHelp.show();
		    }
	    });
	    updatePreferredSize(menuItemHelp,
				menuItemHelp.getText(),
				new Dimension(30,5));
	    
	    menuHelp.addSeparator();
	    
	    JMenuItem menuItemAbout = new JMenuItem("About JavaShare");
	    menuItemAbout.setMnemonic(KeyEvent.VK_A);
	    menuHelp.add(menuItemAbout);
	    menuItemAbout.addActionListener(new ActionListener() {
		public void actionPerformed(ActionEvent e) 
		    {
			dialogAbout.show();
		    }
	    });
	    updatePreferredSize(menuItemAbout,
				menuItemAbout.getText(),
				new Dimension(30,5));
	    
	    JMenuItem menuItemAuthor = new JMenuItem("About the author");
	    menuItemAuthor.setMnemonic(KeyEvent.VK_B);
	    menuHelp.add(menuItemAuthor);
	    menuItemAuthor.addActionListener(new ActionListener() {
		public void actionPerformed(ActionEvent e) 
		    {
			dialogAuthor.show();
		    }
	    });
	    updatePreferredSize(menuItemAuthor,
				menuItemAuthor.getText(),
				new Dimension(30,5));
	    
	    menuBar.add(menuFile);
	    menuBar.add(menuTransfer);
	    menuBar.add(menuHelp);
	    
	    menuFile.setBackground(Color.gray);
	    menuTransfer.setBackground(Color.gray);
	    menuHelp.setBackground(Color.gray);
	    menuBar.setBackground(Color.gray);
	    menuBar.setBorder(BorderFactory.
			      createCompoundBorder(BorderFactory.
						   createEmptyBorder(2,2,1,1),
						   BorderFactory.
						   createEtchedBorder()));
	    
	    // Tree
	    DefaultMutableTreeNode rootNode =
		new DefaultMutableTreeNode("Files");
	    
	    rootNode.add(localNode);
	    rootNode.add(remoteNode);
	    
	    DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
	    
	    tree = new JTree(treeModel);
	    tree.getSelectionModel().
		setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
	    tree.setCellRenderer(new myCellRenderer());
	    tree.setAlignmentX(JTree.LEFT_ALIGNMENT);

	    /*
	      tree.addTreeExpansionListener(((BasicTreeUI) tree.getUI()).new TreeExpansionHandler() {
	      public void treeExpanded(TreeExpansionEvent e)
	      {
	      config.updateLocalDir();
	      }
	      });
	    */

   	    tree.addTreeWillExpandListener(new MyTreeWillExpandListener() {
		public void treeWillExpand(TreeExpansionEvent e)
		    {
			if (e.getPath().getPathCount() == 2 &&
			    e.getPath().getPathComponent(1) == localNode)
			    config.updateLocalDir();
		    }
		public void treeWillCollapse(TreeExpansionEvent e) {}
	    });

 	    tree.addTreeSelectionListener(new MyTreeSelectionListener() {
 		public void valueChanged(TreeSelectionEvent e) {
 		    if (DEBUG == true)
 			System.out.println("selection");
		    TreePath path = e.getPath();

		    TreePath np = e.getNewLeadSelectionPath();
		    if (np == null) setEnabledMenuItemSend(1, false);

		    if (path.getPathCount() < 2) {
			setLabelText("No selection");
			if (np != null) setEnabledMenuItemSend(1, false);
		    } else {
			DefaultMutableTreeNode node =
			    (DefaultMutableTreeNode) path.getLastPathComponent();
			DefaultMutableTreeNode topLevelNode =
			    (DefaultMutableTreeNode) path.getPathComponent(1);
			if (path.getPathCount() == 2) {
			    setLabelText("No selection");
// 			    if (topLevelNode == remoteNode)
// 				setEnabledMenuItemSend(1, true);
// 			    else setEnabledMenuItemSend(1, false);
			    if (np != null) setEnabledMenuItemSend(1, false);
			}
			if (path.getPathCount() > 2) {
			    if (topLevelNode == localNode) {
				if (np != null) setEnabledMenuItemSend(1, true);
				long l = getFileSize("local:"+((String) node.getUserObject()));
				if (l == 0) setLabelText("empty");
				else setLabelText(Long.toString(l) + " bytes");
			    } else if (topLevelNode == remoteNode) {
				if (np != null) setEnabledMenuItemSend(1, true);
				long l = getFileSize("remote:"+((String) node.getUserObject()));
				if (l == 0) setLabelText("empty");
				else setLabelText(Long.toString(l) + " bytes");
			    } else {
				if (np != null) setEnabledMenuItemSend(1, false);
				setLabelText("No selection");
			    }
			}
		    }
 		}
 	    });
	    
	    JScrollPane treeScrollPane = new JScrollPane(tree);
	    treeScrollPane.
		setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
	    treeScrollPane.setPreferredSize(new Dimension(220, 80));
	    
	    treeScrollPane.setAlignmentX(JPanel.LEFT_ALIGNMENT);
	    panel.add(treeScrollPane);
	    
	    // State panel
	    JPanel statePanel = new JPanel();
	    statePanel.setLayout(new BoxLayout(statePanel, BoxLayout.Y_AXIS));
	    statePanel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
	    panel.add(statePanel);
	    
	    statePanel.
		setBorder(BorderFactory.
			  createCompoundBorder(BorderFactory.
					       createTitledBorder("connection state"),
					       BorderFactory.
					       createEmptyBorder(1,1,1,1)));
	    
	    speedBar = new JProgressBar(0, 9600);
	    statePanel.add(speedBar);
	    speedBar.setString("0 bit/s");
	    speedBar.setStringPainted(true);
	    speedBar.setValue(0);
	    
	    statePanel.add(Box.createRigidArea(new Dimension(0,2)));
	    
	    progressBar = new JProgressBar(0, 100);
	    statePanel.add(progressBar);
	    progressBar.setString("Transfer : 0%");
	    progressBar.setStringPainted(true);
	    progressBar.setValue(0);
	    
	    // Text area
	    textArea = new JTextArea("JavaShare © A. Fenyo 2001\n  alex@fenyo.net\n  http://www.fenyo.net/JavaShare.html\n\n");
	    textArea.setEditable(false);
	    //	textArea.setFont(new Font("Serif", Font.ITALIC, 16));
	    textArea.setLineWrap(true);
	    textArea.setWrapStyleWord(true);
	    JScrollPane areaScrollPane = new JScrollPane(textArea);
	    areaScrollPane.
		setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
	    areaScrollPane.setPreferredSize(new Dimension(210, 50));
	    
	    
	    areaScrollPane.setAlignmentX(JPanel.LEFT_ALIGNMENT);
	    panel.add(areaScrollPane);
	    
	    
	    // Label
	    label = new JLabel("JavaShare started");
	    label.setAlignmentX(label.LEFT_ALIGNMENT);
	    label.setMaximumSize(new Dimension(Short.MAX_VALUE, 30));
	    label.setBorder(BorderFactory.
			    createCompoundBorder(BorderFactory.
						 createEmptyBorder(2,2,1,1),
						 BorderFactory.
						 createEtchedBorder()));
	    panel.add(label);
	    
	    timer = new javax.swing.Timer(1000, new ActionListener() {
		public void actionPerformed(ActionEvent e)
		    {
			updateSliders();
		    }
	    });

	    // Finish the whole work
	    frame.pack();
	    frame.show();
	    
	    guiCreated = true;

	    // POP3
	    POPServer popServer = new POPServer();
	    new Thread(popServer).start();

	    appendTextArea("JavaShare started\n");

	    timer.start();
	    // End of GUI creation
	    
	}
	
	catch (NoSuchMethodException e) {
	    logExceptionAndExit(e);
	}

    }



//    public void init() {}

}
