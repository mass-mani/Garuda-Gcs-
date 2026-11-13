#pragma once

#include <QObject>
#include <QTcpServer>
#include <QNetworkAccessManager>
#include <QUrl>

class QTcpSocket;

// Serves only the bundled viewer assets on loopback. Logs stay in the browser;
// this server has no upload endpoint and no access to vehicle commands.
class UAVLogViewerServer : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QUrl url READ url NOTIFY statusChanged)
    Q_PROPERTY(QString error READ error NOTIFY statusChanged)
public:
    explicit UAVLogViewerServer(QObject *parent = nullptr);
    QUrl url() const { return _url; }
    QString error() const { return _error; }
    Q_INVOKABLE void start();
signals:
    void statusChanged();
private:
    void handleRequest(QTcpSocket *socket, const QByteArray &header);
    static void respond(QTcpSocket *socket, int code, const QByteArray &type,
                        const QByteArray &body, bool head = false);
    QTcpServer _server;
    QNetworkAccessManager _network;
    QString _root;
    QUrl _url;
    QString _error;
};
