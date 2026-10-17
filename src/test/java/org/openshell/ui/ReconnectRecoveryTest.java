package org.openshell.ui;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openshell.session.SessionState;
import org.openshell.ssh.SshSession;
import org.openshell.storage.UiPreferences;
import org.openshell.support.LocalSshServer;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.openshell.ui.DailyWorkflowAcceptanceIT.*;

class ReconnectRecoveryTest {
    @TempDir Path temp;
    String before;
    @BeforeEach void isolate() { before=System.getProperty("openshell.configDir"); System.setProperty("openshell.configDir",temp.resolve("config").toString()); }
    @AfterEach void restore() { if(before==null) System.clearProperty("openshell.configDir"); else System.setProperty("openshell.configDir",before); }

    @Test void draftsSurviveFailedAndCancelledRetriesAndRemainIsolatedFromAnotherSession() throws Exception {
        Path root=Files.createDirectory(temp.resolve("remote"));
        AtomicReference<SessionPanel> first=new AtomicReference<>(), second=new AtomicReference<>(); AtomicInteger attempts=new AtomicInteger();
        CountDownLatch retryStarted=new CountDownLatch(1), releaseCancelledRetry=new CountDownLatch(1);
        try(var server=new LocalSshServer(root)) {
            var host=server.host();
            try {
                edt(() -> {
                    first.set(new SessionPanel(host,(c,p)->{},()-> {
                        int attempt=attempts.incrementAndGet();
                        if(attempt==2) return new SshSession(host) {
                            @Override public void connect() throws Exception { throw new java.io.IOException("isolated injected retry failure"); }
                        };
                        if(attempt==3) return new SshSession(host) {
                            @Override public void connect() throws Exception {
                                retryStarted.countDown();
                                if(!releaseCancelledRetry.await(8,TimeUnit.SECONDS)) throw new java.io.IOException("retry release timeout");
                                throw new java.io.IOException("cancelled attempt completed late");
                            }
                        };
                        return new SshSession(host,(h,a,f)->true);
                    }));
                    second.set(new SessionPanel(host,(c,p)->{},()->new SshSession(host,(h,a,f)->true)));
                });
                await(() -> onEdt(() -> first.get().isConnected() && second.get().isConnected() && field(first.get(),"terminalPanel",TerminalPanel.class)!=null && field(second.get(),"terminalPanel",TerminalPanel.class)!=null));
                TerminalPanel old=field(first.get(),"terminalPanel",TerminalPanel.class), other=field(second.get(),"terminalPanel",TerminalPanel.class);
                edt(() -> { field(old,"commandInput",JTextField.class).setText("first unsent 中文"); field(old,"commandEditor",JTextArea.class).setText("multi\nline"); field(other,"commandInput",JTextField.class).setText("other unsent"); first.get().reconnect(); });
                await(() -> first.get().getSessionState()==SessionState.FAILED);
                edt(first.get()::reconnect);
                assertTrue(retryStarted.await(5,TimeUnit.SECONDS));
                edt(() -> field(first.get(),"cancelButton",JButton.class).doClick());
                await(() -> first.get().getSessionState()==SessionState.DISCONNECTED);
                releaseCancelledRetry.countDown();
                edt(first.get()::reconnect);
                await(() -> onEdt(() -> first.get().isConnected() && field(first.get(),"terminalPanel",TerminalPanel.class)!=null));
                edt(() -> {
                    TerminalPanel resumed=field(first.get(),"terminalPanel",TerminalPanel.class);
                    assertEquals("first unsent 中文",field(resumed,"commandInput",JTextField.class).getText());
                    assertEquals("multi\nline",field(resumed,"commandEditor",JTextArea.class).getText());
                    assertEquals("other unsent",field(other,"commandInput",JTextField.class).getText());
                    assertTrue(UiPreferences.current().history(host.getId(),"").isEmpty());
                });
            } finally { releaseCancelledRetry.countDown(); edt(() -> { if(first.get()!=null) first.get().close(); if(second.get()!=null) second.get().close(); }); }
        }
    }

    @Test void editorRetainsOriginalVersionAndDetectsServerChangesAfterReconnect() throws Exception {
        Path root=Files.createDirectory(temp.resolve("remote")); Path file=Files.writeString(root.resolve("edit.txt"),"original");
        AtomicReference<SessionPanel> panel=new AtomicReference<>(); AtomicReference<RemoteEditorPanel> editor=new AtomicReference<>(); AtomicInteger conflicts=new AtomicInteger();
        try(var server=new LocalSshServer(root)) {
            var host=server.host();
            RemoteEditorPanel.EditorDialogs dialogs=new RemoteEditorPanel.EditorDialogs() {
                public RemoteEditorPanel.ConflictChoice conflict(Component parent,String path,boolean deleted) { conflicts.incrementAndGet(); return RemoteEditorPanel.ConflictChoice.CANCEL; }
                public boolean discard(Component parent) { throw new AssertionError("No reload requested"); }
                public String saveAs(Component parent,String path) { throw new AssertionError("No save as requested"); }
                public void compare(Component parent,String local,String remote) { throw new AssertionError("No comparison requested"); }
            };
            try {
                edt(() -> panel.set(new SessionPanel(host,(client,path)->editor.set(new RemoteEditorPanel(client,path,dialogs)),()->new SshSession(host,(h,a,f)->true))));
                await(() -> onEdt(() -> { SftpPanel files=field(panel.get(),"sftpPanel",SftpPanel.class); return files!=null && field(files,"fileTable",JTable.class).getRowCount()==1; }));
                edt(() -> { JTable table=field(field(panel.get(),"sftpPanel",SftpPanel.class),"fileTable",JTable.class); table.setRowSelectionInterval(0,0); table.getActionMap().get("openSelected").actionPerformed(new ActionEvent(table,0,"openSelected")); });
                await(() -> onEdt(() -> editor.get()!=null && field(editor.get(),"loaded",Boolean.class)));
                TerminalPanel old=field(panel.get(),"terminalPanel",TerminalPanel.class);
                edt(() -> field(editor.get(),"editorArea",JTextArea.class).setText("local still unsaved"));
                server.server.getActiveSessions().forEach(s->s.close(true)); await(()->panel.get().getSessionState()==SessionState.DISCONNECTED);
                Files.writeString(file,"external edit while offline"); edt(panel.get()::reconnect);
                await(()->onEdt(()->panel.get().isConnected() && field(panel.get(),"terminalPanel",TerminalPanel.class)!=null && field(panel.get(),"terminalPanel",TerminalPanel.class)!=old));
                edt(() -> { assertTrue(panel.get().ownsClient(editor.get().getSftpClient())); JTextArea area=field(editor.get(),"editorArea",JTextArea.class); area.getActionMap().get("saveFile").actionPerformed(new ActionEvent(area,0,"saveFile")); });
                await(()->conflicts.get()==1);
                assertEquals("external edit while offline",Files.readString(file));
                edt(()->{ assertEquals("local still unsaved",field(editor.get(),"editorArea",JTextArea.class).getText()); assertTrue(field(editor.get(),"modified",Boolean.class)); });
            } finally { if(panel.get()!=null) edt(panel.get()::close); }
        }
    }
}
