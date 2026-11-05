package com.botmaker.core.server;

import com.botmaker.core.CoreJson;
import com.botmaker.core.edit.Edit;
import com.botmaker.core.edit.EditRequest;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.event.CoreEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every message of the channel's protocol reads back equal to what was written. */
class RequestJsonTest {

    @Test
    void everyRequestRoundTrips() {
        List<Request> requests = List.of(
                new Request.Open(1, "/home/me/BotMakerProjects/MyBot"),
                new Request.Show(2, "com/mybot/MyBot.java"),
                new Request.Apply(3, new EditRequest("com/mybot/MyBot.java", List.of(new Edit.Delete("0.1.2")))),
                new Request.UserEdit(4, new Edit.Rename("0.1", "count")),
                new Request.Step(5, HistoryStep.REDO),
                new Request.Commit(6, "com/mybot/MyBot.java", "class A {}", "class B {}", "Rename A"),
                new Request.Close(7));
        for (Request request : requests) {
            assertEquals(request, CoreJson.read(CoreJson.write(request), Request.class));
        }
    }

    @Test
    void everyReplyRoundTrips() {
        List<Reply> replies = List.of(
                new Reply.Answer(1, new EditResult.Refused("No project is open.")),
                new Reply.Answer(2, new EditResult.Accepted(List.of())),
                new Reply.Told(new CoreEvent.Status("Saved.")));
        for (Reply reply : replies) {
            assertEquals(reply, CoreJson.read(CoreJson.write(reply), Reply.class));
        }
    }
}
