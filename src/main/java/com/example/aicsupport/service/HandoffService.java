package com.example.aicsupport.service;

import com.example.aicsupport.access.AccessControlService;
import com.example.aicsupport.common.ApiException;
import com.example.aicsupport.domain.AppUser;
import com.example.aicsupport.domain.Conversation;
import com.example.aicsupport.domain.ConversationStatus;
import com.example.aicsupport.domain.SupportTicket;
import com.example.aicsupport.domain.UserRole;
import com.example.aicsupport.repository.ConversationRepository;
import com.example.aicsupport.repository.TicketRepository;
import com.example.aicsupport.state.ConversationStateCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class HandoffService {

    private final TicketRepository tickets;
    private final ConversationRepository conversations;
    private final ConversationStateCodec stateCodec;
    private final AccessControlService accessControl;
    private final AuditService auditService;

    public HandoffService(
            TicketRepository tickets,
            ConversationRepository conversations,
            ConversationStateCodec stateCodec,
            AccessControlService accessControl,
            AuditService auditService) {
        this.tickets = tickets;
        this.conversations = conversations;
        this.stateCodec = stateCodec;
        this.accessControl = accessControl;
        this.auditService = auditService;
    }

    public List<SupportTicket> list(AppUser actor, String status, String traceId) {
        requireStaff(actor, traceId, "HANDOFF_LIST");
        List<SupportTicket> result = actor.role() == UserRole.ADMIN
                ? tickets.findHandoffs(null, status)
                : tickets.findHandoffs(actor.id(), status);
        accessControl.auditAllowed(actor, traceId, "HANDOFF_LIST", "TICKET", status);
        return result;
    }

    @Transactional
    public SupportTicket reply(
            AppUser actor,
            String ticketId,
            String message,
            boolean resolve,
            String traceId) {
        requireStaff(actor, traceId, "HANDOFF_REPLY");
        SupportTicket ticket = tickets.findByIdForUpdate(ticketId)
                .filter(candidate -> "HANDOFF".equals(candidate.category()))
                .orElseThrow(() -> ApiException.notFound("人工工单不存在"));
        if (actor.role() != UserRole.ADMIN && !actor.id().equals(ticket.assignedAgentId())) {
            auditService.denied(traceId, actor, "HANDOFF_REPLY", "TICKET", ticketId,
                    "assigned agent or admin required");
            throw ApiException.forbidden("仅工单负责人可回复");
        }
        if (ticket.conversationId() == null) {
            throw ApiException.conflict("该工单没有关联会话");
        }
        if ("RESOLVED".equals(ticket.status())) {
            throw ApiException.conflict("工单已解决，不能再次回复或重新打开");
        }
        Conversation conversation = conversations.findByIdForUpdate(ticket.conversationId())
                .orElseThrow(() -> ApiException.notFound("关联会话不存在"));
        LocalDateTime now = LocalDateTime.now();
        conversations.addMessage(conversation.id(), "HUMAN_AGENT", message.trim(), now);
        conversations.update(
                conversation.id(),
                conversation.version(),
                resolve ? ConversationStatus.COMPLETED : conversation.status(),
                conversation.intent(),
                conversation.confidence(),
                stateCodec.decode(conversation.stateJson()),
                conversation.messageCount() + 1,
                now);
        tickets.updateStatus(ticket.id(), resolve ? "RESOLVED" : "PROCESSING", now);
        auditService.record(traceId, actor, "HANDOFF_REPLY", "TICKET", ticket.id(), "ALLOWED",
                Map.of("resolved", resolve, "conversationId", conversation.id()));
        return tickets.findById(ticket.id())
                .orElseThrow(() -> new IllegalStateException("Ticket disappeared after update"));
    }

    private void requireStaff(AppUser actor, String traceId, String action) {
        if (actor.role() == UserRole.CUSTOMER) {
            auditService.denied(traceId, actor, action, "TICKET", null, "staff role required");
            throw ApiException.forbidden("仅客服或管理员可操作人工工单");
        }
    }
}
