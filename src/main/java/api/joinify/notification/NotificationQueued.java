package api.joinify.notification;

/** Se encoló una notificación lista para enviarse ya: el despachador la intenta enseguida, sin esperar al barrido. */
record NotificationQueued(String id) {
}
