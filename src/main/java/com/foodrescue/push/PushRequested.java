package com.foodrescue.push;

import java.util.List;

/** Published inside a transaction; the messages are only sent once that transaction has committed. */
public record PushRequested(List<PushMessage> messages) {
}
