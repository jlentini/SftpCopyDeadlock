SftpCopyDeadlock README
=======================
Author: James Lentini

This is a simple test program to reproduce [VFS-627](https://issues.apache.org/jira/browse/VFS-627).

The deadlock described in VFS-627 can occur when an application uses the 
Apache Commons VFS2 library's [FileObject#copyFrom()](https://commons.apache.org/proper/commons-vfs/commons-vfs2/apidocs/org/apache/commons/vfs2/FileObject.html#copyFrom-org.apache.commons.vfs2.FileObject-org.apache.commons.vfs2.FileSelector-) 
API, and the Commons VFS2 library uses a single JSch SSH connection for the copy. 
In this situation, there are two threads interacting with the SSH session: 
one thread sending file read and write requests and one thread receiving SSH 
packets, including file read responses and SSH channel remote window size 
adjustments. The deadlock occurs when the first thread blocks waiting 
for the remote window size to increase and the second thread blocks waiting 
for buffer space to store prefetched read-ahead data. For each thread to 
be unblocked, the other thread would need to unblock it.

There is a workaround for this issue. A Commons VFS2 application can use two 
`org.apache.commons.vfs2 FileSystemManager` instances (one for each file), 
and hence two SSH connections.


Usage Instructions
==================

To build, run:

```
	$ ./gradlew installDist
```

To execute, run:

```
	$ ./build/install/SftpCopyDeadlock/bin/SftpCopyDeadlock no your-username your-password \
		host.example.com /path/to/source/file /path/to/destination/file
```

Reproducing VFS-627 requires the following:

* The source and destination file must be on the same SSH (SFTP) server.
* A low latency connection to the SSH (SFTP) server. Using localhost works well for this. 
* A source file large enough that the probability of a deadlock is very high. A 2 GB source file should be more than enough.

To activate the workaround for VFS-627, specify "yes" instead of "no" as the 
test program's first command line argument. 


Deadlock Analysis
=================

When the deadlock occurs, a jstack of the running test process shows two waiting threads.

### Read/Write Thread

One thread is waiting for the SSH channel to have sufficient remote window 
space to send a file write to the SFTP server. Here is its jstack:

```
"main" #1 prio=5 os_prio=31 tid=0x00007fd5bd004800 nid=0x2103 in Object.wait() [0x000070000f3b5000]
   java.lang.Thread.State: TIMED_WAITING (on object monitor)
	at java.lang.Object.wait(Native Method)
	at com.jcraft.jsch.Session.write(Session.java:1269)
	- locked <0x000000076eb2c1e0> (a com.jcraft.jsch.ChannelSftp)
	at com.jcraft.jsch.ChannelSftp.sendWRITE(ChannelSftp.java:2646)
	at com.jcraft.jsch.ChannelSftp.access$100(ChannelSftp.java:36)
	at com.jcraft.jsch.ChannelSftp$1.write(ChannelSftp.java:806)
	at java.io.BufferedOutputStream.write(BufferedOutputStream.java:122)
	- locked <0x000000076eb2e380> (a org.apache.commons.vfs2.provider.sftp.SftpFileObject$SftpOutputStream)
	at org.apache.commons.vfs2.util.MonitorOutputStream.write(MonitorOutputStream.java:123)
	- locked <0x000000076eb2e380> (a org.apache.commons.vfs2.provider.sftp.SftpFileObject$SftpOutputStream)
	at java.io.BufferedOutputStream.flushBuffer(BufferedOutputStream.java:82)
	at java.io.BufferedOutputStream.write(BufferedOutputStream.java:126)
	- locked <0x000000076eb2f3d0> (a org.apache.commons.vfs2.provider.DefaultFileContent$FileContentOutputStream)
	at org.apache.commons.vfs2.util.MonitorOutputStream.write(MonitorOutputStream.java:123)
	- locked <0x000000076eb2f3d0> (a org.apache.commons.vfs2.provider.DefaultFileContent$FileContentOutputStream)
	at org.apache.commons.vfs2.provider.DefaultFileContent.write(DefaultFileContent.java:805)
	at org.apache.commons.vfs2.provider.DefaultFileContent.write(DefaultFileContent.java:784)
	at org.apache.commons.vfs2.provider.DefaultFileContent.write(DefaultFileContent.java:755)
	at org.apache.commons.vfs2.provider.DefaultFileContent.write(DefaultFileContent.java:771)
	at org.apache.commons.vfs2.FileUtil.copyContent(FileUtil.java:37)
	at org.apache.commons.vfs2.provider.AbstractFileObject.copyFrom(AbstractFileObject.java:295)
	at SftpCopyDeadlock.main(SftpCopyDeadlock.java:42)
```

In the above, the "main" thread is performing a file copy using the Commons VFS2 library's 
[FileObject#copyFrom()](https://commons.apache.org/proper/commons-vfs/commons-vfs2/apidocs/org/apache/commons/vfs2/FileObject.html#copyFrom-org.apache.commons.vfs2.FileObject-org.apache.commons.vfs2.FileSelector-)
API. The thread is in the `DefaultFileContent#write(.)` function on line 805 of 
[DefaultFileContent.java](https://gitbox.apache.org/repos/asf?p=commons-vfs.git;a=blob;f=commons-vfs2/src/main/java/org/apache/commons/vfs2/provider/DefaultFileContent.java;h=870f4ea7a52e8de44ceaf763a79ec0ced9986061;hb=eabdee306d5b0a73859a0aa841a5c0ccfe8b337a#l797)

```
 797     public long write(final OutputStream output, final int bufferSize) throws IOException {
 798         final InputStream input = this.getInputStream();
 799         long count = 0;
 800         try {
 801             // This read/write code from Apache Commons IO
 802             final byte[] buffer = new byte[bufferSize];
 803             int n = 0;
 804             while (-1 != (n = input.read(buffer))) {
>805                 output.write(buffer, 0, n);
 806                 count += n;
 807             }
 808         } finally {
 809             input.close();
 810         }
 811         return count;
 812     }
```

As shown in the code fragment above, the "main" thread is executing a read/write 
loop on line 804. When the deadlock occurs, this thread is waiting for remote 
window space so that it can send a file write to the SFTP server. As required 
in the SSHv2 standard, each channel is flow controlled using a window mechanism. 
To adjust the window size, each side sends a window resize message to provide 
more window space for the other side to send. This "main" thread will 
therefore not be unblocked until a window resize is received.

Before the deadlock, this thread has also been reading data. The `byte` 
array created on line 802 above is 4 KB. However, the size of each read 
request and the number of read requests can be increased by lower layers 
of the software stack. In this instance, the Java I/O libraries (version 1.8) 
increase each I/O size to 8 KB and the JSch library (version 0.1.55) 
increases the size to 32 KB and can increase the number of read requests 
to pre-fetch even more data. Although reading ahead to prefetch 
data is a sensible way to improve performance, it requires sufficient buffer 
space to place the additional data. The deadlock occurs because there is not 
always enough space to buffer this read-ahead data.


### Packet Receiver Thread

A second thread is in a loop receiving incoming SSH packets from the TCP socket.
Here is its jstack:

```
"Connect thread localhost session" #13 daemon prio=5 os_prio=31 tid=0x00007fd5bd0b7800 nid=0x5803 in Object.wait() [0x00007000108f5000]
   java.lang.Thread.State: TIMED_WAITING (on object monitor)
	at java.lang.Object.wait(Native Method)
	at java.io.PipedInputStream.awaitSpace(PipedInputStream.java:273)
	at java.io.PipedInputStream.receive(PipedInputStream.java:231)
	- locked <0x000000076eb0be60> (a com.jcraft.jsch.Channel$MyPipedInputStream)
	at java.io.PipedOutputStream.write(PipedOutputStream.java:149)
	at com.jcraft.jsch.IO.put(IO.java:64)
	at com.jcraft.jsch.Channel.write(Channel.java:438)
	at com.jcraft.jsch.Session.run(Session.java:1459)
	at java.lang.Thread.run(Thread.java:748)
```


This thread is blocked waiting for free buffer space to store 
read response data from the SFTP server. 

If the read/write thread consumed more read data, then buffer space 
would be freed up and this thread would be unblocked. However, the 
read/write thread is blocked waiting for the remote window size to 
be adjusted. Unfortunately, the SSH packet with the window adjustment 
will never be processed because the packet receive thread is blocked 
and will therefore never dequeue the channel window adjustment.

When the deadlock occurs, the netstat command shows a large 
amount of data on the TCP socket's receive queue waiting to 
be processed, for example:

```
Active Internet connections (servers and established)
Proto Recv-Q Send-Q Local Address           Foreign Address         State      
tcp   478920      0 10.43.76.9:41550        10.43.90.179:ssh        ESTABLISHED
```


Solution
========

This change to the JSch library will prevent the deadlock by allocating sufficient buffer space to store read response data from the SFTP server (patch against JSch version 0.1.55):

```
diff --git a/src/main/java/com/jcraft/jsch/ChannelSftp.java b/src/main/java/com/jcraft/jsch/ChannelSftp.java
index f76d1d5..3ac036f 100644
--- a/src/main/java/com/jcraft/jsch/ChannelSftp.java
+++ b/src/main/java/com/jcraft/jsch/ChannelSftp.java
@@ -224,7 +224,7 @@ public class ChannelSftp extends ChannelSession{
 
       PipedOutputStream pos=new PipedOutputStream();
       io.setOutputStream(pos);
-      PipedInputStream pis=new MyPipedInputStream(pos, rmpsize);
+      PipedInputStream pis=new MyPipedInputStream(pos, rq.size()*rmpsize);
       io.setInputStream(pis);
 
       io_in=io.in;
```
