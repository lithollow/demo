package com.example.aicsupport.api;

import com.example.aicsupport.access.AccessControlService;
import com.example.aicsupport.api.dto.ConversationView;
import com.example.aicsupport.api.dto.MessageView;
import com.example.aicsupport.common.ApiException;
import com.example.aicsupport.config.RequestTraceFilter;
import com.example.aicsupport.domain.AppUser;
import com.example.aicsupport.domain.Conversation;
import com.example.aicsupport.repository.ConversationRepository;
import com.example.aicsupport.state.ConversationStateCodec;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final AccessControlService accessControl;
    private final ConversationRepository conversations;
    private final ConversationStateCodec stateCodec;

    public ConversationController(
            AccessControlService accessControl,
            ConversationRepository conversations,
            ConversationStateCodec stateCodec) {
        this.accessControl = accessControl;
        this.conversations = conversations;
        this.stateCodec = stateCodec;
    }

    @GetMapping("/{conversationId}")
    ConversationView get(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String conversationId,
            HttpServletRequest request) {
        AppUser actor = accessControl.requireUser(userId);
        String traceId = RequestTraceFilter.traceId(request);
        Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        accessControl.requireConversationAccess(actor, conversation, traceId);
        accessControl.auditAllowed(actor, traceId, "CONVERSATION_READ",
                "CONVERSATION", conversation.id());
        var state = stateCodec.decode(conversation.stateJson());
        return new ConversationView(
                conversation.id(),
                conversation.userId(),
                conversation.status(),
                conversation.intent(),
                conversation.confidence(),
                state.pendingAction(),
                conversation.lastQuestion(),
                state.slots(),
                conversation.messageCount(),
                conversation.version(),
                conversation.createdAt(),
                conversation.updatedAt(),
                conversations.messages(conversation.id()).stream()
                        .map(message -> new MessageView(
                                message.role(), message.content(), message.createdAt()))
                        .toList());
    }
}
