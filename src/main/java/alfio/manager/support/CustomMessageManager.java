/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager.support;

import alfio.manager.EventManager;
import alfio.manager.NotificationManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.i18n.MessageSourceManager;
import alfio.manager.system.ConfigurationLevel;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.system.Mailer;
import alfio.model.*;
import alfio.model.modification.MessageModification;
import alfio.model.user.Organization;
import alfio.repository.TicketCategoryRepository;
import alfio.repository.TicketRepository;
import alfio.util.Json;
import alfio.util.TemplateManager;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.stereotype.Component;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static alfio.manager.system.Mailer.AttachmentIdentifier.CALENDAR_ICS;
import static alfio.model.system.ConfigurationKeys.BASE_URL;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

@Component
@AllArgsConstructor
@Log4j2
public class CustomMessageManager {

    private final TemplateManager templateManager;
    private final EventManager eventManager;
    private final TicketRepository ticketRepository;
    private final TicketReservationManager ticketReservationManager;
    private final NotificationManager notificationManager;
    private final TicketCategoryRepository ticketCategoryRepository;
    private final Executor sendMessagesExecutor = Executors.newSingleThreadExecutor();
    private final ConfigurationManager configurationManager;
    private final MessageSourceManager messageSourceManager;

    public Map<String, Object> generatePreview(String eventName, Optional<Integer> categoryId, List<MessageModification> input, String username) {
        Map<String, Object> result = new HashMap<>();
        Event event = eventManager.getSingleEvent(eventName, username);
        result.put("affectedUsers", categoryId.map(id -> ticketRepository.countAssignedTickets(event.getId(), id)).orElseGet(() -> ticketRepository.countAllAssigned(event.getId())));
        result.put("preview", preview(event, input, username));
        return result;
    }

    public void sendMessages(String eventName, Optional<Integer> categoryId, List<MessageModification> input, String username) {

        Event event = eventManager.getSingleEvent(eventName, username);
        preview(event, input, username);//dry run for checking the syntax
        Organization organization = eventManager.loadOrganizer(event, username);
        Map<String, List<MessageModification>> byLanguage = input.stream().collect(Collectors.groupingBy(m -> m.getLocale().getLanguage()));
        var baseUrl = configurationManager.getFor(BASE_URL, ConfigurationLevel.event(event)).getRequiredValue();
        var eventMetadata = Optional.ofNullable(eventManager.getMetadataForEvent(event).getRequirementsDescriptions());

        sendMessagesExecutor.execute(() -> {
            var messageSource = messageSourceManager.getMessageSourceForEvent(event);
            categoryId.map(id -> ticketRepository.findConfirmedByCategoryId(event.getId(), id))
                .orElseGet(() -> ticketRepository.findAllConfirmed(event.getId()))
                .stream()
                .filter(t -> isNotBlank(t.getFullName()) && isNotBlank(t.getEmail()))
                .parallel()
                .map(t -> {
                    Model model = new ExtendedModelMap();
                    model.addAttribute("eventName", eventName);
                    model.addAttribute("fullName", t.getFullName());
                    model.addAttribute("organizationName", organization.getName());
                    model.addAttribute("organizationEmail", organization.getEmail());
                    model.addAttribute("reservationURL", ticketReservationManager.reservationUrl(t.getTicketsReservationId(), event));
                    model.addAttribute("reservationID", ticketReservationManager.getShortReservationID(event, t.getTicketsReservationId()));
                    model.addAttribute("ticketURL", ticketReservationManager.ticketUpdateUrl(event, t.getUuid()));
                    model.addAttribute("ticketID", t.getUuid());
                    return Triple.of(t, t.getEmail(), model);
                })
                .forEach(triple -> {
                    Ticket ticket = triple.getLeft();
                    MessageModification m = Optional.ofNullable(byLanguage.get(ticket.getUserLanguage())).orElseGet(() -> byLanguage.get(byLanguage.keySet().stream().findFirst().orElseThrow(IllegalStateException::new))).get(0);
                    Model model = triple.getRight();
                    String subject = renderResource(m.getSubject(), event, model, m.getLocale(), templateManager);
                    StringBuilder text = new StringBuilder(renderResource(m.getText(), event, model, m.getLocale(), templateManager));
                    List<Mailer.Attachment> attachments = new ArrayList<>();
                    if(m.isAttachTicket()) {
                        boolean onlineEvent = event.isOnline();
                        var optionalReservation = ticketReservationManager.findById(ticket.getTicketsReservationId());
                        var optionalTicketCategory = ticketCategoryRepository.getByIdAndActive(ticket.getCategoryId());

                        if(optionalReservation.isPresent() && optionalTicketCategory.isPresent() && onlineEvent) {
                            var checkInUrl = TicketReservationManager.ticketOnlineCheckInUrl(event, ticket, baseUrl);
                            var instructions = Optional.ofNullable(ticketCategoryRepository.getMetadata(event.getId(), optionalTicketCategory.get().getId()).getRequirementsDescriptions())
                                .flatMap(metadata -> Optional.ofNullable(metadata.get(ticket.getUserLanguage())))
                                .or(() -> eventMetadata.flatMap(metadata -> Optional.ofNullable(metadata.get(ticket.getUserLanguage()))))
                                .orElse("");
                            // generate only calendar invitation, as Ticket PDF would not make sense in this case.
                            attachments.add(generateCalendarAttachmentForOnlineEvent(ticket, optionalReservation.get(), optionalTicketCategory.get(), organization, checkInUrl, instructions));
                            // add check-in URL and prerequisites, if any
                            Map<String, String> onlineCheckInModel = Map.of("onlineCheckInUrl", checkInUrl, "prerequisites", instructions);
                            text.append(notificationManager.buildOnlineCheckInText(onlineCheckInModel, Locale.forLanguageTag(ticket.getUserLanguage()), messageSource));
                        } else if(optionalReservation.isPresent() && optionalTicketCategory.isPresent()) {
                            attachments.add(generateTicketAttachment(ticket, optionalReservation.get(), optionalTicketCategory.get(), organization));
                        }
                    }
                    notificationManager.sendSimpleEmail(event, ticket.getTicketsReservationId(), triple.getMiddle(), subject, (TextTemplateGenerator)text::toString, attachments);
                });
        });

    }

    private List<MessageModification> preview(Event event, List<MessageModification> input, String username) {
        Model model = new ExtendedModelMap();
        Organization organization = eventManager.loadOrganizer(event, username);
        model.addAttribute("eventName", event.getDisplayName());
        model.addAttribute("fullName", "John Doe");
        model.addAttribute("organizationName", organization.getName());
        model.addAttribute("organizationEmail", organization.getEmail());
        model.addAttribute("reservationURL", "https://this-is-the-reservation-url");
        model.addAttribute("ticketURL", "https://this-is-the-ticket-url");
        model.addAttribute("reservationID", "RESID");
        model.addAttribute("ticketID", "TICKETID");
        return input.stream()
                .map(m -> MessageModification.preview(m, renderResource(m.getSubject(), event, model, m.getLocale(), templateManager),
                    renderResource(m.getText(), event, model, m.getLocale(), templateManager), m.isAttachTicket()))
                .collect(Collectors.toList());
    }

    public static Mailer.Attachment generateTicketAttachment(Ticket ticket, TicketReservation reservation, TicketCategory ticketCategory, Organization organization) {
        Map<String, String> model = getModelForTicket(ticket, reservation, ticketCategory, organization);
        return new Mailer.Attachment("ticket-" + ticket.getUuid() + ".pdf", null, "application/pdf", model, Mailer.AttachmentIdentifier.TICKET_PDF);
    }

    private static Map<String, String> getModelForTicket(Ticket ticket, TicketReservation reservation, TicketCategory ticketCategory, Organization organization) {
        Map<String, String> model = new HashMap<>();
        model.put("ticket", Json.toJson(ticket));
        model.put("ticketCategory", Json.toJson(ticketCategory));
        model.put("reservationId", reservation.getId());
        model.put("organizationId", Integer.toString(organization.getId()));
        return model;
    }

    public static Mailer.Attachment generateCalendarAttachmentForOnlineEvent(Ticket ticket,
                                                                             TicketReservation reservation,
                                                                             TicketCategory ticketCategory,
                                                                             Organization organization,
                                                                             String checkInUrl,
                                                                             String prerequisites) {
        var model = getModelForTicket(ticket, reservation, ticketCategory, organization);
        model.put("onlineCheckInUrl", checkInUrl);
        model.put("prerequisites", prerequisites);
        return new Mailer.Attachment(CALENDAR_ICS.fileName(""), null, CALENDAR_ICS.contentType(""), model, CALENDAR_ICS);
    }

    private static String renderResource(String template, EventAndOrganizationId event, Model model, Locale locale, TemplateManager templateManager) {
        return templateManager.renderString(event, template, model.asMap(), locale, TemplateManager.TemplateOutput.TEXT);
    }
}
