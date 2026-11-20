-- Canonical 50 member ids (member-service seed must use exactly these).
-- ids 1-45 are grouped; ids 46-50 are UNGROUPED (no group_members row).
--   1 27ysni0g1i
--   2 itn3xvk9tf
--   3 tdhwzxuyxx
--   4 a9pwo15d0x
--   5 c77u434yrz
--   6 xa3c7tgnke
--   7 jvsv1ibc5q
--   8 brelafdryp
--   9 tdw9dihcxa
--  10 nk8a48yt86
--  11 09uu3cg8ge
--  12 xz2pvkdv50
--  13 s9wr3d811t
--  14 0lptc9wkbc
--  15 61np6rl0b5
--  16 lo0zhf9u5h
--  17 sglblrt5i0
--  18 ouyr87j6jl
--  19 ejnh5f4dk3
--  20 v12f4vetc6
--  21 mdz95u9ub0
--  22 22qnjgxo9b
--  23 g2v75ybctk
--  24 rmisikc6z4
--  25 5l85frcqg3
--  26 05dvr43xvc
--  27 rx62wr0vvy
--  28 nueprak0u0
--  29 uq1zgu1ptz
--  30 rviup7zsqp
--  31 copjff22tr
--  32 iu1yuz0kcp
--  33 0ll8pygbgo
--  34 snxvabajgr
--  35 h3l3mkyocm
--  36 bo7pk6ku0d
--  37 knfb76gh47
--  38 ie5qdl7nox
--  39 9y3pbks94o
--  40 vdlv2js1kn
--  41 mq1dlnxivx
--  42 uk1rapgql0
--  43 kokghrpag6
--  44 28tix1bav4
--  45 sodygynv2v
--  46 pq12td4qds  UNGROUPED
--  47 xui0i5ekhw  UNGROUPED
--  48 ovyqikgqw0  UNGROUPED
--  49 uoedsbosg5  UNGROUPED
--  50 069q3kmqbi  UNGROUPED

insert into groups(id,name,pricing,registered_at) values
('g1a2b3c4d5','Alpha Team','Free','2025-01-10 09:00:00+09'),
('g6e7f8h9j0','Beta Squad','Advanced','2025-02-14 10:30:00+09'),
('k1m2n3p4q5','Gamma Guild','Premium','2025-03-03 13:15:00+09'),
('r6s7t8u9v0','Delta Crew','Free','2025-04-21 08:45:00+09'),
('w1x2y3z4a5','Epsilon Lab','Premium','2025-05-30 16:00:00+09');

insert into group_members(member_id,group_id,permission,joined_at) values
('27ysni0g1i','g1a2b3c4d5','Admin','2025-06-01 12:00:00+09'),
('itn3xvk9tf','g1a2b3c4d5','Editor','2025-06-01 12:00:00+09'),
('tdhwzxuyxx','g1a2b3c4d5','Viewer','2025-06-02 12:00:00+09'),
('a9pwo15d0x','g1a2b3c4d5','Viewer','2025-06-02 12:00:00+09'),
('c77u434yrz','g1a2b3c4d5','Editor','2025-06-03 12:00:00+09'),
('xa3c7tgnke','g1a2b3c4d5','Viewer','2025-06-03 12:00:00+09'),
('jvsv1ibc5q','g1a2b3c4d5','Viewer','2025-07-06 09:30:00+09'),
('brelafdryp','g1a2b3c4d5','Editor','2025-07-07 09:30:00+09'),
('tdw9dihcxa','g1a2b3c4d5','Viewer','2025-07-08 09:30:00+09'),
('nk8a48yt86','g1a2b3c4d5','Viewer','2025-07-09 09:30:00+09'),
('09uu3cg8ge','g1a2b3c4d5','Editor','2025-07-10 09:30:00+09'),
('xz2pvkdv50','g1a2b3c4d5','Viewer','2025-07-11 09:30:00+09'),
('s9wr3d811t','g6e7f8h9j0','Admin','2025-06-01 12:00:00+09'),
('0lptc9wkbc','g6e7f8h9j0','Editor','2025-06-01 12:00:00+09'),
('61np6rl0b5','g6e7f8h9j0','Viewer','2025-06-02 12:00:00+09'),
('lo0zhf9u5h','g6e7f8h9j0','Viewer','2025-06-02 12:00:00+09'),
('sglblrt5i0','g6e7f8h9j0','Editor','2025-06-03 12:00:00+09'),
('ouyr87j6jl','g6e7f8h9j0','Viewer','2025-06-03 12:00:00+09'),
('ejnh5f4dk3','g6e7f8h9j0','Viewer','2025-07-06 09:30:00+09'),
('v12f4vetc6','g6e7f8h9j0','Editor','2025-07-07 09:30:00+09'),
('mdz95u9ub0','g6e7f8h9j0','Viewer','2025-07-08 09:30:00+09'),
('22qnjgxo9b','g6e7f8h9j0','Viewer','2025-07-09 09:30:00+09'),
('g2v75ybctk','k1m2n3p4q5','Admin','2025-06-01 12:00:00+09'),
('rmisikc6z4','k1m2n3p4q5','Editor','2025-06-01 12:00:00+09'),
('5l85frcqg3','k1m2n3p4q5','Viewer','2025-06-02 12:00:00+09'),
('05dvr43xvc','k1m2n3p4q5','Viewer','2025-06-02 12:00:00+09'),
('rx62wr0vvy','k1m2n3p4q5','Editor','2025-06-03 12:00:00+09'),
('nueprak0u0','k1m2n3p4q5','Viewer','2025-06-03 12:00:00+09'),
('uq1zgu1ptz','k1m2n3p4q5','Viewer','2025-07-06 09:30:00+09'),
('rviup7zsqp','k1m2n3p4q5','Editor','2025-07-07 09:30:00+09'),
('copjff22tr','k1m2n3p4q5','Viewer','2025-07-08 09:30:00+09'),
('iu1yuz0kcp','r6s7t8u9v0','Admin','2025-06-01 12:00:00+09'),
('0ll8pygbgo','r6s7t8u9v0','Editor','2025-06-01 12:00:00+09'),
('snxvabajgr','r6s7t8u9v0','Viewer','2025-06-02 12:00:00+09'),
('h3l3mkyocm','r6s7t8u9v0','Viewer','2025-06-02 12:00:00+09'),
('bo7pk6ku0d','r6s7t8u9v0','Editor','2025-06-03 12:00:00+09'),
('knfb76gh47','r6s7t8u9v0','Viewer','2025-06-03 12:00:00+09'),
('ie5qdl7nox','r6s7t8u9v0','Viewer','2025-07-06 09:30:00+09'),
('9y3pbks94o','r6s7t8u9v0','Editor','2025-07-07 09:30:00+09'),
('vdlv2js1kn','w1x2y3z4a5','Admin','2025-06-01 12:00:00+09'),
('mq1dlnxivx','w1x2y3z4a5','Editor','2025-06-01 12:00:00+09'),
('uk1rapgql0','w1x2y3z4a5','Viewer','2025-06-02 12:00:00+09'),
('kokghrpag6','w1x2y3z4a5','Viewer','2025-06-02 12:00:00+09'),
('28tix1bav4','w1x2y3z4a5','Editor','2025-06-03 12:00:00+09'),
('sodygynv2v','w1x2y3z4a5','Viewer','2025-06-03 12:00:00+09');
