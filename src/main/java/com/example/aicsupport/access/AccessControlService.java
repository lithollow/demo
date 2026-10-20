package com.example.aicsupport.access;

import com.example.aicsupport.common.ApiException;
import com.example.aicsupport.domain.AppUser;
import com.example.aicsupport.domain.Conversation;
import com.example.aicsupport.domain.CustomerOrder;
import com.example.aicsupport.domain.SupportTicket;
import com.example.aicsupport.domain.UserRole;
import com.example.aicsupport.repository.OrderRepository;
import com.example.aicsupport.repository.TicketRepository;
import com.example.aicsupport.repository.UserRepository;
import com.example.aicsupport.service.AuditService;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AccessControlService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final AuditService auditService;

    public AccessControlService(
            UserRepository userRepository,
            OrderRepository orderRepository,
            TicketRepository ticketRepository,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
        this.auditService = auditService;
    }

    public AppUser requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw ApiException.unauthorized("缺少用户身份");
        }
        return userRepository.findById(userId)
                .filter(AppUser::active)
                .orElseThrow(() -> ApiException.unauthorized("未知或已停用的用户"));
    }

    public void requireAdmin(AppUser actor, String traceId, String action) {
        if (actor.role() != UserRole.ADMIN) {
            auditService.denied(traceId, actor, action, "ADMIN_RESOURCE", null, "admin role required");
            throw ApiException.forbidden("仅管理员可执行该操作");
        }
    }

    public void requireConversationAccess(AppUser actor, Conversation conversation, String traceId) {
        if (actor.role() == UserRole.ADMIN || actor.id().equals(conversation.userId())) {
            return;
        }
        if (actor.role() == UserRole.AGENT) {
            boolean assigned = ticketRepository.findHandoffByConversation(conversation.id())
                    .map(ticket -> actor.id().equals(ticket.assignedAgentId()))
                    .orElse(false);
            if (assigned) {
                return;
            }
        }
        auditService.denied(traceId, actor, "CONVERSATION_READ", "CONVERSATION",
                conversation.id(), "conversation owner or assigned agent required");
        throw ApiException.forbidden("无权查看该会话");
    }

    public void requireOrderView(AppUser actor, CustomerOrder order, String traceId) {
        if (actor.role() == UserRole.ADMIN || actor.id().equals(order.userId())) {
            return;
        }
        if (actor.role() == UserRole.AGENT
                && orderRepository.hasAssignedAgentTicket(order.id(), actor.id())) {
            return;
        }
        auditService.denied(traceId, actor, "ORDER_READ", "ORDER", order.id(),
                "order owner or assigned agent required");
        throw ApiException.forbidden("无权查看该订单");
    }

    public void requireTicketView(AppUser actor, SupportTicket ticket, String traceId) {
        if (actor.role() == UserRole.ADMIN
                || actor.id().equals(ticket.userId())
                || (actor.role() == UserRole.AGENT && actor.id().equals(ticket.assignedAgentId()))) {
            return;
        }
        auditService.denied(traceId, actor, "TICKET_READ", "TICKET", ticket.id(),
                "ticket owner or assigned agent required");
        throw ApiException.forbidden("无权查看该工单");
    }

    public void auditAllowed(
            AppUser actor,
            String traceId,
            String action,
            String resourceType,
            String resourceId) {
        auditService.record(traceId, actor, action, resourceType, resourceId, "ALLOWED",
                Map.of("role", actor.role().name()));
    }
}

