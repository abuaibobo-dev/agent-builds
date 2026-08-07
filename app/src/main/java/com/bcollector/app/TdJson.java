package com.bcollector.app;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

final class TdJson {
    private interface TdLib extends Library {
        Pointer td_json_client_create();
        void td_json_client_send(Pointer client, String request);
        String td_json_client_receive(Pointer client, double timeout);
        String td_json_client_execute(Pointer client, String request);
        void td_json_client_destroy(Pointer client);
    }

    private static final TdLib LIB = Native.load("tdjson", TdLib.class);

    private TdJson() {}

    static Pointer tdJsonClientCreate() {
        return LIB.td_json_client_create();
    }

    static void tdJsonClientSend(Pointer client, String request) {
        LIB.td_json_client_send(client, request);
    }

    static String tdJsonClientReceive(Pointer client, double timeout) {
        return LIB.td_json_client_receive(client, timeout);
    }

    static String tdJsonClientExecute(Pointer client, String request) {
        return LIB.td_json_client_execute(client, request);
    }

    static void tdJsonClientDestroy(Pointer client) {
        LIB.td_json_client_destroy(client);
    }
}
